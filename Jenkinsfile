pipeline {
    agent any
 
    stages {
        stage('Test') {
            steps {
 
                // To run Maven on a Windows agent, use
                bat "mvn -D clean test"
            }
 
          
        }
    }
}
