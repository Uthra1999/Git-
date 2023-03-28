#!groovy
String failureCause = ""
pipeline {
    agent {
        label 'docker'
    }

    parameters {
        booleanParam(name: 'PUBLISH_INTERNAL', defaultValue: false, description: "Upload this build to the Google Play store.")
        booleanParam(name: 'SKIP_UI_TESTS', defaultValue: false, description: "Skip the UI test suite.")
        string(name: 'COMMIT', defaultValue: "", description: "REQUIRED - commit hash to generate change list.")
        string(name: 'EMAIL_MESSAGE', defaultValue: "", description: "Optional email message.")
        string(name: 'SUBMITTER', defaultValue: "", description: "Your Amazon user ID")
    }

    options {
        timeout(time: 10, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    environment {
        KERB_TOKEN_FILE = sh(returnStdout: true, script: 'klist | grep "Ticket cache" | awk -F: \'{print $3}\'').trim()
    }

    stages {
        stage('container') {
            agent {
                dockerfile {
                    filename 'Dockerfile'
                    reuseNode true
                    args '--device /dev/kvm --device /dev/bus/usb \
-v /usr/share/ca-certificates/amazon:/usr/share/ca-certificates/amazon \
-v /etc/krb5.conf:/etc/krb5.conf -v /etc/ssl/certs:/etc/ssl/certs \
-v $KERB_TOKEN_FILE:$KERB_TOKEN_FILE -e KRB5CCNAME=FILE:$KERB_TOKEN_FILE \
-v $HOME/secrets.tar.gz:/tmp/secrets.tar.gz \
-v $HOME/.gradle/$EXECUTOR_NUMBER:/home/botuser/.gradle'
                    additionalBuildArgs '--build-arg HOST_USER_ID=$(id -u $USER) \
--build-arg HOST_GROUP_ID=$(id -g $USER) \
--build-arg COMPILE_SDK_VERSION=$(grep compileSdkVersion GoodreadsOnKindleTablet/build.gradle | awk \'{print $2}\')'
                }
            }

            environment {
                COMMIT_MESSAGE = sh(script: 'git log -1 --format=%s', returnStdout: true)?.trim()
                CRUX_ID = sh(script: 'git log -1 | sed -n \'s/^.*cr\\:* https\\:\\/\\/code.amazon.com\\/reviews\\/CR\\-\\([0-9]*\\).*$/\\1/p\'', returnStdout: true)?.trim()
            }

            stages {
                stage('Setup') {
                    steps {
                        postCodeReviewMessage('Started build.')
                        slackSend color: 'good', message: "Build Started - ${getBuildInfo()}"
                        echo 'Code Review URL: ' + getCodeReviewUrl()

                        sh 'env | sort'

                        copySecrets()
                        copyArtifacts(projectName: "${JOB_NAME}", selector: specific("${BUILD_NUMBER}"), optional: true)

                        // Download dependencies
                        retry(2) {
                            sh './gradlew'
                        }
                    }

                    post {
                        success {
                            saveStage()
                        }
                        failure {
                            reportFailure("Error during setup stage.")
                        }
                    }
                }

                stage('Static Checks') {
                    when {
                        expression {
                            !isStageCompleted()
                        }
                    }

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
                        success {
                            saveStage()
                        }
                        failure {
                            reportFailure("Java files not formatted or strings not updated.")
                        }
                    }
                }

                stage('Build') {
                    when {
                        expression {
                            !isStageCompleted()
                        }
                    }

                    steps {
                        sh './gradlew assemble assembleAndroidTest'

                        // Compress mapping to save disk space
                        sh 'cd GoodreadsOnKindleTablet/build/outputs && tar -czvf mapping.tar.gz mapping'
                    }

                    post {
                        success {
                            saveStage()
                        }
                        failure {
                            reportFailure("Build not able to compile.")
                        }
                    }
                }

                stage('Lint') {
                    when {
                        expression {
                            !isStageCompleted()
                        }
                    }
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
                            saveStage()
                        }
                        failure {
                            recordIssues enabledForFailure: true, aggregatingResults: true, tools: [androidLintParser(pattern: '**/lint-results-*.xml')]
                            reportFailure("Lint failed, report generated.")
                        }
                    }
                }

                stage('Unit Tests') {
                    when {
                        expression {
                            !isStageCompleted()
                        }
                    }

                    steps {
                        // Local
                        sh './gradlew jacocoTestAndroidDebugUnitTestReport'
                        sh './gradlew jacocoTestFireDebugUnitTestReport'
                        sh './gradlew GrokPlatform:test'

                        // Instrumented
                        launchEmulator()
                        sh './gradlew createAndroidDebugCoverageReport -Pandroid.testInstrumentationRunnerArguments.size=small -PenableTestCoverage'
                        sh './gradlew mergeTestReport'
                        sh 'mv GoodreadsOnKindleTablet/build/reports/coverage/android/debug GoodreadsOnKindleTablet/build/reports/coverage/android/debug_small'
                        sh 'mv GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/androidDebugAndroidTest GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/ANDROID_SMALL'
                    }

                    post {
                        success {
                            saveStage()
                        }
                        failure {
                            reportFailure("Unit tests failed.")
                        }
                        aborted {
                            reportFailure("Unit tests aborted. This could be caused by a timeout.")
                        }
                    }
                }

                stage('Coverage verification') {
                    when {
                        expression {
                            !isStageCompleted()
                        }
                    }

                    steps {
                        sh './gradlew jacocoAndroidTestCoverageVerification'
                    }

                    post {
                        success {
                            saveStage()
                        }
                        failure {
                            reportFailure("Code coverage requirements not met.")
                        }
                        aborted {
                            reportFailure("Build was aborted. This could be caused by a timeout.")
                        }
                    }
                }

                stage('UI Smoke Tests') {
                    when {
                        expression {
                            !isStageCompleted()
                        }
                    }

                    steps {
                        launchEmulator()
                        sh './gradlew connectedAndroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.annotation=com.goodreads.kindle.test.SmokeTest'
                        sh 'mv GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/androidDebugAndroidTest GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/ANDROID_SMOKE'
                    }

                    post {
                        success {
                            saveStage()
                        }
                        failure {
                            sh 'adb logcat -v color -d *:W'
                            reportFailure("Smoke tests failed.")
                        }
                        aborted {
                            reportFailure("Smoke tests aborted. This could be caused by a timeout.")
                        }
                    }
                }

                stage('UI Tests') {
                    when {
                        expression {
                            !isCodeReview() && !isStageCompleted()
                        }
                    }

                    steps {
                        script {
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

                            launchEmulator()

                            sh './gradlew assembleFireDebugAndroidTest assembleAndroidDebugAndroidTest'
                            lock("devices-${env.NODE_NAME}") {
                                try {
                                    enableUsb()
                                    parallel fireFlavor: {
                                        def fireSerials = sh(script: 'adb devices -l | grep model:KF | awk \'{print $1}\' | paste -s -d, -', returnStdout: true)?.trim()
                                        if (!fireSerials) {
                                            throw new IOException('FireOS device(s) not available.')
                                        }

                                        sh "ANDROID_SERIAL=${fireSerials} ./gradlew uninstallAll connectedFireDebugAndroidTest -x assembleFireDebugAndroidTest"
                                    }, androidFlavor: {
                                        def androidSerials = sh(script: 'adb devices -l | grep device: | grep -v model:KF | awk \'{print $1}\' | paste -s -d, -', returnStdout: true)?.trim()
                                        if (!androidSerials) {
                                            throw new IOException('Android device(s) not available.')
                                        }

                                        sleep 10 // avoid Gradle cache lock conflicts
                                        sh "ANDROID_SERIAL=${androidSerials} ./gradlew connectedAndroidDebugAndroidTest -x assembleAndroidDebugAndroidTest"
                                    }
                                } finally {
                                    disableUsb()
                                }
                            }

                            sh 'adb emu kill'
                        }
                    }

                    post {
                        success {
                            saveStage()
                        }
                        failure {
                            disableUsb()
                            sh 'adb logcat color -d *:E'
                            reportFailure("Instrumented tests failed.")
                        }
                        regression {
                            reportRegression()
                        }
                        aborted {
                            reportFailure("Instrumented tests aborted. This could be caused by a timeout.")
                        }
                    }
                }

                stage('Deploy') {
                    when {
                        expression {
                            env.BRANCH_NAME ==~ /(mainline|^ship_.*|^feature_.*)/
                        }
                    }

                    steps {
                        script {

                            // Check first for pre-submitted values
                            if (params.COMMIT) {
                                sh 'project/build/deploy.sh "' \
                 + params.COMMIT + '" "' \
                 + params.EMAIL_MESSAGE + '" "' \
                 + params.SUBMITTER + '" "' \
                 + params.PUBLISH_INTERNAL + '"'
                                return
                            } else {
                                // Offer a second chance to input if this was a system-generated build
                                def userInput
                                timeout(time: 1, unit: 'HOURS') {
                                    userInput = input(
                                            id: 'userInput', message: 'Let\'s deploy?', submitterParameter: 'submitter', parameters: [
                                            [$class: 'StringParameterDefinition', defaultValue: '', description: 'Optional, previous released SHA to compare to for email changelog.', name: 'previous_version'],
                                            [$class: 'TextParameterDefinition', defaultValue: '', description: 'Optional, will send email including this message.', name: 'email_message'],
                                            [$class: 'BooleanParameterDefinition', defaultValue: true, description: 'Publish to Google Play store internal test track, only consumed by our team.', name: 'publish_internal']
                                    ]
                                    )
                                }

                                sh 'project/build/deploy.sh "' \
                 + userInput['previous_version'] + '" "' \
                 + userInput['email_message'] + '" "' \
                 + userInput['submitter'] + '" "' \
                 + userInput['publish_internal'] + '"'
                            }
                        }
                    }
                }
            }

            post {
                always {
                    junit allowEmptyResults: true, testResults: 'GoodreadsOnKindleTablet/build/outputs/androidTest-results/connected/flavors/**/*.xml, GoodreadsOnKindleTablet/build/test-results/**/*.xml'
                    sh 'rm -rf GoodreadsOnKindleTablet/build/outputs/mapping'
                    // Large file size, don't need to archive
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'GoodreadsOnKindleTablet/build/outputs/**, GoodreadsOnKindleTablet/build/reports/**, GoodreadsOnKindleTablet/build/test-results/**, completed_stages.txt'

                    // Test results
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/tests/testAndroidDebugUnitTest/', reportFiles: 'index.html', reportName: 'Local Android Test Results', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/tests/testFireDebugUnitTest/', reportFiles: 'index.html', reportName: 'Local Fire Test Results', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/androidDebugAndroidTest/', reportFiles: 'index.html', reportName: 'Instrumented Android Test Results', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/ANDROID_SMALL/', reportFiles: 'index.html', reportName: 'Small Instrumented Android Test Results', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/androidTests/connected/flavors/FIRE/', reportFiles: 'index.html', reportName: 'Instrumented Fire Test Results', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GrokPlatform/build/reports/tests/test/', reportFiles: 'index.html', reportName: 'GrokPlatform Test Results', reportTitles: ''])

                    // Test Coverage
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/jacoco/jacocoTestAndroidDebugUnitTestReport/html/', reportFiles: 'index.html', reportName: 'Local Android Test Coverage', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/jacoco/jacocoTestFireDebugUnitTestReport/html/', reportFiles: 'index.html', reportName: 'Local Fire Test Coverage', reportTitles: ''])
                    // publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/android/debug/', reportFiles: 'index.html', reportName: 'Instrumented Android Test Coverage', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/android/debug_small/', reportFiles: 'index.html', reportName: 'Small Instrumented Android Test Coverage', reportTitles: ''])
                    // publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/coverage/fire/debug/', reportFiles: 'index.html', reportName: 'Instrumented Fire Test Coverage', reportTitles: ''])
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GrokPlatform/build/reports/jacoco/test/html/', reportFiles: 'index.html', reportName: 'GrokPlatform Unit Test Coverage', reportTitles: ''])

                    // Combined report
                    publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'GoodreadsOnKindleTablet/build/reports/combinedCoverageReport/', reportFiles: 'index.html', reportName: 'Combined Coverage', reportTitles: ''])

                    echo 'Code Review URL: ' + getCodeReviewUrl()
                }
                success {
                    postCodeReviewMessage("Congrats! All stages passed!")
                    slackSend color: 'good', message: "Build Success - ${getBuildInfo()} \r\n ${checkCoverage()}"
                    cleanWs()
                }
                failure {
                    cleanWs()
                    slackSend color: 'danger', message: "Build Failure - ${getBuildInfo()} \r\n ${failureCause}"
                }
            }
        }
    }
}

def reportFailure(String cause) {
    failureCause = cause
    echo "Cause of failure - ${failureCause}"
    postCodeReviewMessage(failureCause)
}

def isCodeReview() {
    return env.GIT_URL.contains('/snapshot/') && env.CRUX_ID
}

def postCodeReviewMessage(String message) {
    if (!isCodeReview() || params.SMOKE) {
        return
    }

    sh """#!/bin/bash -vx
    curl --anyauth --location-trusted -u: -c cookies.txt -b cookies.txt -v --capath /usr/share/ca-certificates/amazon "https://code.amazon.com/reviews/CR-${
        env.CRUX_ID
    }" &> cr.html
    csrf_token=\$(grep "csrf-token" cr.html | awk -F\\" '{print \$4}')
    revision_number=\$(grep "location: https://code.amazon.com/reviews/CR-${env.CRUX_ID}/revisions/" cr.html | awk -F/ '{print \$NF}' | tr -d '\\r')
    curl --anyauth --location-trusted -u: -c cookies.txt -b cookies.txt -v --capath /usr/share/ca-certificates/amazon -H 'Accept-Encoding: gzip, deflate, br' -H 'Content-type: application/json' -H "X-CSRF-TOKEN: \${csrf_token}" --data-binary "{\\"spiffy\\":true,\\"location\\":\\"v4:TOP::::::\\",\\"parent\\":null,\\"content\\":\\"${
        message
    } See ${
        env.BUILD_URL
    }\\",\\"importance\\":0}" --compressed "https://code.amazon.com/reviews/CR-${env.CRUX_ID}/revisions/\${revision_number}/comments"
    curl --anyauth --location-trusted -u: -c cookies.txt -b cookies.txt -v --capath /usr/share/ca-certificates/amazon -H 'Accept-Encoding: gzip, deflate, br' -H 'Content-type: application/json' -H "X-CSRF-TOKEN: \${csrf_token}" --data-binary "{\\"revisions\\":[\${revision_number}]}" --compressed "https://code.amazon.com/reviews/CR-${
        env.CRUX_ID
    }/publish_comments"
  """
}

def getCodeReviewUrl() {
    if (!isCodeReview()) {
        return null
    }

    return 'https://code.amazon.com/reviews/CR-' + env.CRUX_ID
}

def getBuildInfo() {
    def buildInfo = "${env.JOB_NAME} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)\n${env.COMMIT_MESSAGE}"
    return isCodeReview() ? buildInfo + " (<${getCodeReviewUrl()}|CR>)" : buildInfo
}

def checkCoverage() {
    sh "python check_coverage.py GoodreadsOnKindleTablet/build/reports/combinedCoverageReport/report.xml coverage_results.txt"
    return readFile('coverage_results.txt')
}

def launchEmulator() {
    if (isEmulatorRunning()) {
        return
    }

    disableUsb()
    enableKvm()
    // Workaround for emulator sometimes timing out on startup
    // catch block is required - see https://issues.jenkins-ci.org/browse/JENKINS-51454
    retry(3) {
        try {
            timeout(time: 5, unit: 'MINUTES') {
                sh '''#!/bin/bash -vx
          pkill -9 "qemu*"
          emulator @Pixel -gpu swiftshader_indirect -skin 1080x2340 -no-window -no-audio -no-snapshot -memory 2048 -cache-size 1024 -partition-size 1024 -camera-back none -camera-front none &
          adb wait-for-device
          while [ "$(adb shell getprop dev.bootcomplete | tr -d '\r')" != 1 ]; do sleep 5; done
        '''
            }
        } catch (err) {
            echo "Timeout exceeded - retrying... $err"
        }
    }

    setDefaultTestAccount()
    disableAnimations()
    disableOkGoogle()
}

def setDefaultTestAccount() {
    sh '''#!/bin/bash -vx
    sleep 30
    adb shell setprop debug.gr.test.account CI_ELLA
  '''
}

// Disables animations at the emulator level by setting the scale (speed) to 0
def disableAnimations() {
    sh '''#!/bin/bash -vx
    adb shell settings put global window_animation_scale 0 &
    adb shell settings put global transition_animation_scale 0 &
    adb shell settings put global animator_duration_scale 0 &
  '''
}

// Disables hotword detection ("Ok Google") app which causes looping noisy errors like
// com.google.android.apps.gsa.shared.speech.a.g: Error reading from input stream
def disableOkGoogle() {
    sh 'adb shell su root pm disable com.google.android.googlequicksearchbox'
}

def isEmulatorRunning() {
    return sh(script: 'ps -e', returnStdout: true).contains('qemu')
}

def copySecrets() {
    sh '''#!/bin/bash -vx
    sudo chown botuser:botgroup /home/botuser/.gradle
    mkdir /home/botuser/temp
    sudo tar -xzf /tmp/secrets.tar.gz -C /home/botuser/temp
    sudo chown -R botuser:botgroup /home/botuser/temp
    cp -r /home/botuser/temp/. /home/botuser
    rm -r /home/botuser/temp
  '''
}

def saveStage() {
    sh 'echo $STAGE_NAME >> completed_stages.txt'
}

def isStageCompleted() {
    return readFile('completed_stages.txt').contains("${STAGE_NAME}")
}

/** Enable connections to host devices. */
def enableUsb() {
    sh 'sudo chmod 755 /dev/bus/usb && adb kill-server && sleep 5 && adb devices -l'
}

/** Disable connections to host devices. */
def disableUsb() {
    sh 'adb kill-server && sudo chmod 750 /dev/bus/usb'
}

/** Enables Kernel-based Virtual Machine (KVM) hypervisor for emulator acceleration. */
def enableKvm() {
    sh 'sudo chmod 666 /dev/kvm'
}

def getCommitter() {
    return sh (
            script:  'git --no-pager show -s --format=\'%ae\' | sed -E \'s/^(.*)@amazon|@goodreads\\.com$/\\1/\'\n',
            returnStdout: true
    ).trim()
}

def reportRegression() {
    committer = getCommitter()
    slackSend color: 'danger', message: "REGRESSION - Automated tests are newly failing in this build.  @$committer \r\n ${getBuildInfo()}"
}