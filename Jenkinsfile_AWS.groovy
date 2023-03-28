#!groovy
String failureCause = ""
def smokeTestsPassed = true
pipeline {
    parameters {
        string(name: 'COMMIT', defaultValue: "", description: "(Deploy Only) REQUIRED - commit hash to generate change list.")
        string(name: 'EMAIL_MESSAGE', defaultValue: "", description: "(Deploy Only) Optional email message.")
        string(name: 'SUBMITTER', defaultValue: "", description: "(Deploy Only) Your Amazon user ID.")
        booleanParam(name: 'PUBLISH_INTERNAL', defaultValue: false, description: "(Deploy Only) Upload this build to the Google Play store.")
        booleanParam(name: 'SKIP_UI_TESTS', defaultValue: false, description: "(Deploy & CRs) Skip the UI test suite.")
        choice(name: 'DEVICE',
                choices:
                        ['Pixel4aOS12',
                         'Pixel6OS12',
                         'Pixel6ProOS12',
                         'Pixel7OS13',
                         'Pixel6aOS13',
                         'SamsungGalaxyS225GOS13',
                         'Pixel4aOS11',
                         'SamsungA51OS10',
                         'SamsungGalaxyNote20OS11',
                         'Pixel3aOS10',
                         'SamsungGalaxyS21OS11',
                         'SamsungGalaxyS10eOS9',
                         'SamsungGalaxyTabS7OS11',
                         'SamsungGalaxyNote9OS8.1.0'],
                description: 'Select a device/OS combination to test on (Optional)')
    }

    environment {
        COMMIT_MESSAGE = sh(script: 'git log -1 --format=%s', returnStdout: true)?.trim()
    }

    options {
        timeout(time: 15, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    agent {
        dockerfile {
            filename 'Dockerfile'
            reuseNode true
            args '-v /usr/share/ca-certificates/amazon:/usr/share/ca-certificates/amazon \
-v /etc/ssl/certs:/etc/ssl/certs \
-v $HOME/secrets.tar.gz:/tmp/secrets.tar.gz \
-v $HOME/.gradle/$EXECUTOR_NUMBER:/home/botuser/.gradle'
            additionalBuildArgs '--build-arg HOST_USER_ID=$(id -u $USER) \
--build-arg HOST_GROUP_ID=$(id -g $USER) \
--build-arg COMPILE_SDK_VERSION=$(grep compileSdkVersion GoodreadsOnKindleTablet/build.gradle | awk \'{print $2}\')'
        }
    }

    stages {
        stage('Setup') {
            steps {
                postCodeReviewInProgress('Started build.')
                //slackSend color: 'good', message: "Build Started - ${getBuildInfo()}"
                echo 'Code Review URL: ' + getCodeReviewUrl()

                sh 'env | sort'

                copySecrets()

                // Download dependencies
                retry(2) {
                    sh './gradlew'
                }
            }

            post {
                failure {
                    reportFailure("Error during setup stage.")
                }
                aborted {
                    reportFailure("Aborted during setup stage.")
                }
            }
        }

        stage('Static Checks') {
            steps {
                sh './gradlew formatJavaFiles -PformatAllJavaFiles'
                sh './gradlew formatKotlinFiles'
                sh './gradlew generatePseudoStrings generateEnglishUsStrings'
                sh 'git diff --exit-code GoodreadsOnKindleTablet/src'
                sh '''
                  if grep -rnx --include "*.java" "import android\\.test\\.suitebuilder\\.annotation\\..*;" GoodreadsOnKindleTablet/src; then
                    echo "Error: Annotations deprecated, use android.support.test.filters package annotations instead."
                    exit 1
                  fi
                '''
            }

            post {
                failure {
                    reportFailure("Java files not formatted or strings not updated.")
                }
                aborted {
                    reportFailure("Aborted during static checks stage. ")
                }
            }
        }

        stage('Build') {
            steps {
                sh './gradlew assemble assembleAndroidTest -PenableTestCoverage'

                // Compress mapping to save disk space
                sh 'cd GoodreadsOnKindleTablet/build/outputs && tar -czvf mapping.tar.gz mapping'
            }

            post {
                failure {
                    reportFailure("Build failed to compile.")
                }
                aborted {
                    reportFailure("Aborted during build stage. This could be caused by a timeout.")
                }
            }
        }

        // Re-enable as part of https://sim.amazon.com/issues/MOB-5979
        stage('Lint') {
            steps {
                echo "Running lint inspection"
                script {
                    sh './gradlew :GoodreadsOnKindleTablet:lintAndroidRelease'
                    sh './gradlew :GoodreadsOnKindleTablet:lintFireRelease'
                }
            }

            post {
                success {
                    recordIssues enabledForFailure: true, aggregatingResults: true, tools: [androidLintParser(pattern: '**/lint-results-*.xml')]
                }
                failure {
                    recordIssues enabledForFailure: true, aggregatingResults: true, tools: [androidLintParser(pattern: '**/lint-results-*.xml')]
                    reportFailure("Lint failed, report generated.")
                }
                aborted {
                    reportFailure("Aborted during lint stage. This could be caused by a timeout.")
                }
            }
        }

        stage('Local Unit Tests') {
            steps {
                sh './gradlew jacocoTestAndroidDebugUnitTestReport'
                sh './gradlew jacocoTestFireDebugUnitTestReport'
                sh './gradlew GrokPlatform:test'
            }

            post {
                failure {
                    reportFailure("Unit tests failed.")
                }
                aborted {
                    reportFailure("Aborted during unit tests stage. This could be caused by a timeout.")
                }
            }
        }

        stage('Instrumented Unit & UI Smoke Tests') {
            steps {
                // We run the unit (aka @Small) tests then UI @Smoke tests in the same Device Farm run to save on setup
                // and teardown time. If the small tests fail we will immediately fail the build and skip running smoke
                // tests, that way you don't have to wait a long time to find out unit tests are failing.
                deviceFarm('SmallAndSmoke.yml')
            }

            post {
                failure {
                    reportFailure("Small or smoke tests failed.")
                    script {
                        smokeTestsPassed = false
                    }
                    conditionallyFailStageAndContinueToUITests()
                }
                aborted {
                    reportFailure("Small or smoke tests aborted. This could be caused by a timeout.")
                }
            }
        }

        stage('Coverage verification') {
            steps {
                sh './gradlew jacocoAndroidTestCoverageVerification'
            }

            post {
                success {
                    postCodeReviewSuccess("Congrats! All stages passed!")
                    //slackSend color: 'good', message: "Build Success - ${getBuildInfo()} \r\n ${checkCoverage()}"
                }
                failure {
                    reportFailure("Code coverage requirements not met.")
                }
                aborted {
                    reportFailure("Aborted during coverage verification stage.")
                }
            }
        }

        stage('UI Tests') {
            when {
                expression {
                    !isCodeReview()
                }
            }

            steps {
                script {
                    echo "SmokeTestsPassed evaluates to ${smokeTestsPassed}"
                    // Gives user time to cancel test run in case this is a stage restart
                    if (params.SKIP_UI_TESTS) {
                        return
                    }
                    try {
                        timeout(10) {
                            input('Abort to skip stage, will continue in 10 minutes.')
                        }
                    } catch (err) {
                        def user = err.getCauses()[0].getUser()
                        if ('SYSTEM' != user.toString()) {
                            // User clicked abort, skip stage
                            return
                        }
                    }
                    // remaining UI tests, split into 6 parts for device farm compatibility
                    deviceFarm('TestRailShardIndex0Total6.yml')
                    deviceFarm('TestRailShardIndex1Total6.yml')
                    deviceFarm('TestRailShardIndex2Total6.yml')
                    deviceFarm('TestRailShardIndex3Total6.yml')
                    deviceFarm('TestRailShardIndex4Total6.yml')
                    deviceFarm('TestRailShardIndex5Total6.yml')
                    sh './gradlew htmlTestRailAndroidTestReport htmlCombinedAndroidTestReport'

                    if (!smokeTestsPassed) {
                        failJobRun('delayed job run failure for smoke test failure')
                    }
                }
            }

            post {
                failure {
                    reportFailure("Instrumented tests failed.")
                    failJobRun('UI Tests Failed')
                }
                regression {
                    reportRegression()
                }
                aborted {
                    reportFailure("Instrumented tests aborted. " +
                            "This could be caused by a timeout: " +
                            "Pipeline Timeout is 15 hours. " +
                            "Individual pipeline DF Runs timeout after 135 minutes.")
                }
            }
        }

        stage('Deploy') {
            when {
                expression {
                    (env.BRANCH_NAME ==~ /(mainline|^ship_.*|^feature_.*)/) && (params.COMMIT)
                }
            }

            steps {
                script {
                    sh 'project/build/deploy_AWS.sh "' \
                         + params.COMMIT + '" "' \
                         + params.EMAIL_MESSAGE + '" "' \
                         + params.SUBMITTER + '" "' \
                         + params.PUBLISH_INTERNAL + '"'
                    //slackSend color: 'good', message: "${getMessageBodyText()}"
                }
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: "GoodreadsOnKindleTablet/build/outputs/androidTest-results/connected/flavors/androidDebugAndroidTest/**/*.xml, GoodreadsOnKindleTablet/build/test-results/**/*.xml, GrokPlatform/build/test-results/test/*.xml"

            // Large file size, don't need to archive
            sh 'rm -rf GoodreadsOnKindleTablet/build/outputs/mapping'

            archiveArtifacts allowEmptyArchive: true, artifacts: 'Device Farm/**'
            archiveArtifacts allowEmptyArchive: true, artifacts: 'GoodreadsOnKindleTablet/build/outputs/**, GoodreadsOnKindleTablet/build/reports/**, GoodreadsOnKindleTablet/build/test-results/**, GoodreadsOnKindleTablet/build/jacoco/**'
            archiveArtifacts allowEmptyArchive: true, artifacts: 'GrokPlatform/build/libs/**, GrokPlatform/build/reports/**, GrokPlatform/build/test-results/**, GrokPlatform/build/jacoco/**'

            // Test results
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/tests/testAndroidDebugUnitTest/', reportFiles: 'index.html', reportName: 'Local Android Test Results', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/tests/testFireDebugUnitTest/', reportFiles: 'index.html', reportName: 'Local Fire Test Results', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/androidDebugAndroidTest/Small/', reportFiles: 'index.html', reportName: 'Small Instrumented Android Test Results', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/androidDebugAndroidTest/Smoke/', reportFiles: 'index.html', reportName: 'Smoke Instrumented Android Test Results', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/androidDebugAndroidTest/Combined/', reportFiles: 'index.html', reportName: 'Combined Instrumented Android Test Results', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GrokPlatform/build/reports/tests/test/', reportFiles: 'index.html', reportName: 'GrokPlatform Test Results', reportTitles: ''])

            // Test Coverage
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/tests/android/jacocoHtml/', reportFiles: 'index.html', reportName: 'Local Android Test Coverage', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/tests/fire/jacocoHtml/', reportFiles: 'index.html', reportName: 'Local Fire Test Coverage', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/android/debug/Small/', reportFiles: 'index.html', reportName: 'Small Instrumented Android Test Coverage', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GrokPlatform/build/reports/jacoco/test/html/', reportFiles: 'index.html', reportName: 'GrokPlatform Unit Test Coverage', reportTitles: ''])
            publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/combined/', reportFiles: 'index.html', reportName: 'Combined Coverage', reportTitles: ''])
        }
        cleanup {
            // Delete the workspace contents after every build to avoid running out of disk space
            cleanWs()
        }
    }
}

// See test spec files in project/build/device_farm_test_spec. Updates aren't automatically uploaded (yet).
def deviceFarm(String testSpecFilename, Integer timeoutMinutes = 135) {
    env.DEVICE_FARM_NAMESPACE = testSpecFilename.replace('_staging', '').replace('.yml', '')
    try {
        echo "testing starting on ${params.DEVICE}"
        def deviceFarmOutput = devicefarm appArtifact: 'GoodreadsOnKindleTablet/build/outputs/apk/android/debug/GoodreadsOnKindleTablet-android-armeabi-v7a-debug.apk',
                appiumJavaJUnitTest: '',
                appiumJavaTestNGTest: '',
                appiumNodeTest: '',
                appiumPythonTest: '',
                appiumRubyTest: '',
                appiumVersionJunit: '1.4.16',
                appiumVersionPython: '1.4.16',
                appiumVersionTestng: '1.4.16',
                calabashFeatures: '',
                calabashProfile: '',
                calabashTags: '',
                deviceLatitude: 47.6204,
                deviceLocation: false,
                deviceLongitude: -122.3941,
                devicePoolName: "${params.DEVICE}",
                environmentToRun: 'CustomEnvironment',
                eventCount: '',
                eventThrottle: '',
                extraData: false,
                extraDataArtifact: '',
                ifAppPerformanceMonitoring: true,
                ifBluetooth: true,
                ifGPS: true,
                ifNfc: true,
                ifSkipAppResigning: false,
                ifVideoRecording: true,
                ifVpce: false,
                ifWebApp: false,
                ifWifi: true,
                ignoreRunError: false,
                isRunUnmetered: true,
                jobTimeoutMinutes: timeoutMinutes,
                junitArtifact: 'GoodreadsOnKindleTablet/build/outputs/apk/androidTest/android/debug/GoodreadsOnKindleTablet-android-debug-androidTest.apk',
                junitFilter: '',
                password: '',
                projectName: 'GoodreadsDF',
                radioDetails: false,
                runName: "${BUILD_TAG}_" + env.DEVICE_FARM_NAMESPACE,
                seed: '',
                storeResults: true,
                testSpecName: testSpecFilename,
                testToRun: 'INSTRUMENTATION',
                uiautomationArtifact: '',
                uiautomatorArtifact: '',
                uiautomatorFilter: '',
                username: '',
                vpceServiceName: '',
                xctestArtifact: '',
                xctestFilter: '',
                xctestUiArtifact: '',
                xctestUiFilter: ''

        // If we accidentally type the wrong test spec name it _doesn't_ fail the build so we need to check manually
        String testSpecNotFoundError = "[AWSDeviceFarm] TestSpec '" + testSpecFilename + "' not found."
        if (deviceFarmOutput?.contains(testSpecNotFoundError)) {
            throw new IllegalArgumentException(testSpecNotFoundError)
        }
    } catch (err) {
        echo "Caught: ${err}"
        currentBuild.result = 'FAILURE'
    } finally {
        // Device Farm archives the results while the build is running so to access them in our workspace we need to
        // unarchive them (little unconventional, but fine). It also makes the assumption that you'll only run a test
        // suite in Device Farm once per build. A second run writes to the same 'AWS Device Farm Results' artifact
        // folder erasing any files that matched the previous run. We have to manually move the results to a namespaced
        // directly after the run so we don't lose the results.
        unarchive mapping: ['AWS Device Farm Results/' : '.']
        sh '''#!/bin/bash -vx
            set -euo pipefail
            mkdir -p "Device Farm/${DEVICE_FARM_NAMESPACE}"
            cp -r AWS\\ Device\\ Farm\\ Results/*/* "Device Farm/${DEVICE_FARM_NAMESPACE}/"
            unzip "Device Farm/${DEVICE_FARM_NAMESPACE}/Tests Suite/Tests/Customer Artifacts-00001.zip" -d "Device Farm/${DEVICE_FARM_NAMESPACE}/Tests Suite/Tests"
            
            coverage_file="Device Farm/${DEVICE_FARM_NAMESPACE}/Tests Suite/Tests/Host_Machine_Files/\\$DEVICEFARM_LOG_DIR/coverage.ec"
            if [[ -f "$coverage_file" ]]; then
                mkdir -p "GoodreadsOnKindleTablet/build/outputs/code_coverage/androidDebugAndroidTest/connected"
                cp "$coverage_file" "GoodreadsOnKindleTablet/build/outputs/code_coverage/androidDebugAndroidTest/connected/"
            fi
        '''
        if (env.DEVICE_FARM_NAMESPACE == "SmallAndSmoke") {
            // XML and HTML reports
            sh './gradlew deviceFarmReports'
            // Coverage HTML reports
            sh './gradlew smallAndroidDebugCoverageReport mergeTestReport'
        }
    }
}

def reportFailure(String cause) {
    failureCause = cause
    echo "Cause of failure - ${failureCause}"
    postCodeReviewFailed(cause)
    //slackSend color: 'danger', message: "${failureCause} - ${getBuildInfo()}"
}

def isCodeReview() {
    return env.crux != null && !env.crux.isEmpty() && !env.crux.trim().isEmpty()
}

def postCodeReviewSuccess(String message) {
    postCodeReview("Success", message)
}
def postCodeReviewFailed(String message) {
    postCodeReview("Failed", message)
}
def postCodeReviewInProgress(String message) {
    postCodeReview("In Progress", message)
}
/**
 * See https://builderhub.corp.amazon.com/docs/crux/user-guide/create-analyzer-howto.html
 *
 * @param status anything other than "Success", "Failed", or "In Progress" will be reported as a FAULT status in crux
 * @param message max 1000 characters
 */
def postCodeReview(String status, String message) {
    if (!isCodeReview()) {
        return
    }

    def payload = "{\"Records\":[{\"Sns\":{\"Message\":\"${env.crux.replaceAll("\"", "\\\\\"")}\",\"MessageAttributes\":{\"BuildMessage\":{\"Type\":\"String\",\"Value\":\"${message} ${env.BUILD_URL}\"},\"BuildStatus\":{\"Type\":\"String\",\"Value\":\"${status}\"}}}}]}"
    sh "aws lambda invoke --function-name AnalyzerLambda --region=us-west-2 --payload '${payload}' response.json"
}

def getCodeReviewUrl() {
    if (!isCodeReview()) {
        return null
    }

    def reviewId = sh (script: 'echo "$crux" | jq -r ".cruxEventSource.reviewId"', returnStdout: true).trim()
    return "https://code.amazon.com/reviews/${reviewId}"
}

def getBuildInfo() {
    def buildInfo = "${env.JOB_NAME} #${env.BUILD_NUMBER} ${params.DEVICE} (<${env.BUILD_URL}|Open>)\n${env.COMMIT_MESSAGE}"
    return isCodeReview() ? buildInfo + " (<${getCodeReviewUrl()}|CR>)" : buildInfo
}

def checkCoverage() {
    sh "python check_coverage.py GoodreadsOnKindleTablet/build/reports/coverage/combined/report.xml coverage_results.txt"
    return readFile('coverage_results.txt')
}

def getMessageBodyText() {
    return readFile('body.txt')
}

def copySecrets() {
    sh '''#!/bin/bash -vx
    sudo chown botuser:botgroup /home/botuser/.gradle
    mkdir /home/botuser/temp
    sudo tar -xzf /tmp/secrets.tar.gz -C /home/botuser/temp
    sudo chown -R botuser:botgroup /home/botuser/temp
    cp -r /home/botuser/temp/secrets/. /home/botuser
    rm -r /home/botuser/temp
  '''
}

def getCommitter() {
    return sh (
            script:  'git --no-pager show -s --format=\'%ae\' | sed -E \'s/^(.*)@amazon|@goodreads\\.com$/\\1/\'\n',
            returnStdout: true
    ).trim()
}

def reportRegression() {
    committer = getCommitter()
    //slackSend color: 'danger', message: "REGRESSION - Automated tests are newly failing in this build.  @$committer \r\n ${getBuildInfo()}"
}

def failStageOnly() {
    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
        echo 'evaluated to catchError'
        echo 'failing stage only'
        error 'FAIL'
    }
}

def failJobRun(String failureReason) {
    reason = failureReason
    echo "Failing whole job run because - ${reason}"
    sh 'exit 1'
}

def conditionallyFailStageAndContinueToUITests() {
    if (isCodeReview() || params.SKIP_UI_TESTS) {
        failJobRun('smoke failure results in job run failure')
    } else {
        echo 'ok to continue to next stage since UI Tests will be run'
        failStageOnly()
    }
}