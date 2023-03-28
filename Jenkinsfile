pipeline {
    agent any
 
    stages {
        stage('Test') {
            steps {
 
                // To run Maven on a Windows agent, use
                bat "mvn -D clean test"
            }
 
           post {
                 
                // If Maven was able to run the tests, even if some of the test
                // failed, record the test results and archive the jar file.
                success {
                        cucumber buildStatus: 'null', 
                        customCssFiles: '', 
                        customJsFiles: '', 
                        failedFeaturesNumber: -1, 
                        failedScenariosNumber: -1, 
                        failedStepsNumber: -1, 
                        fileIncludePattern: '**/*.json', 
                        pendingStepsNumber: -1, 
                        skippedStepsNumber: -1, 
                        sortingMethod: 'ALPHABETICAL', 
                        undefinedStepsNumber: -1
                }
              
            }
          stage('Instrumented Unit & UI Smoke Tests') {
            steps {
                // We run the unit (aka @Small) tests then UI @Smoke tests in the same Device Farm run to save on setup
                // and teardown time. If the small tests fail we will immediately fail the build and skip running smoke
                // tests, that way you don't have to wait a long time to find out unit tests are failing.
                deviceFarm('Paris.yml')
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

        }
    }
}
