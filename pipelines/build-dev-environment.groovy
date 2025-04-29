pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        disableResume()
    }

    parameters {
        string name: 'ENVIRONMENT_NAME', trim: true     
        password defaultValue: '123', description: 'Password to use for MySQL container - root user', name: 'MYSQL_PASSWORD'
        string name: 'MYSQL_PORT', trim: true  

        booleanParam(name: 'SKIP_STEP_1', defaultValue: false, description: 'STEP 1 - RE-CREATE DOCKER IMAGE')
    }
  
    stages {
        stage('Checkout GIT repository') {
            steps {     
              script {
                git branch: 'master',
                credentialsId: 'abbb60bd-90ab-4b24-8be4-42962b129dc4',
                url: 'git@github.com:miguelsoares77/oracle-test.git'
              }
            }
        }
        stage('Validate variables') {
            steps {
                script {
                    def mysqlPort = "${params.MYSQL_PORT}"

                    // check if port is integer
                    if (!mysqlPort.isInteger()) {
                        error "The MySQL port '${mysqlPort}' is not integer."
                    }
                    def mysqlPortInt = mysqlPort.toInteger()
                    // check port interval (1-65535)
                    if (mysqlPortInt < 1 || mysqlPortInt > 65535) {
                        error "The MySQL port '${mysqlPortInt}' must be between 1 and 65535."
                    }

                    echo "MySQL port '${mysqlPort}' is valid."
                }
            }
        }
        stage('Create latest Docker image') {
            steps {     
              script {
                if (!params.SKIP_STEP_1){    
                    echo "Creating docker image with name $params.ENVIRONMENT_NAME using port: $params.MYSQL_PORT"
                    sh """
                    sed 's/<PASSWORD>/$params.MYSQL_PASSWORD/g' pipelines/include/create_developer.template > pipelines/include/create_developer.sql
                    """

                    sh """
                    docker build pipelines/ -t $params.ENVIRONMENT_NAME:latest
                    """

                }else{
                    echo "Skipping STEP1"
                }
              }
            }
        }
        stage('Start new container using latest image and create user') {
            steps {     
              script {
                def dateTime = (sh(script: "date +%Y%m%d%H%M%S", returnStdout: true).trim())
                def containerName = "${params.ENVIRONMENT_NAME}_${dateTime}"
                sh """
                docker run -itd --name ${containerName} \\
                  -e MYSQL_ROOT_PASSWORD=$params.MYSQL_PASSWORD \\
                  -p $params.MYSQL_PORT:3306 \\
                  --health-cmd="mysqladmin --user=root --password="$params.MYSQL_PASSWORD" ping" \\
                  --health-interval=30s \\
                  --health-retries=3 \\
                  --health-timeout=5s \\
                  $params.ENVIRONMENT_NAME:latest
                  """

                sh """
                timeout 100 bash -c '
                  until status=\$(docker inspect -f "{{.State.Health.Status}}" ${containerName} 2>/dev/null) && \\
                    [ "\$status" = "healthy" ]; do  
                    sleep 10;
                    echo "Waiting for container to become healthy..."
                done
                '
                """

                sh """
                docker exec ${containerName} /bin/bash -c 'mysql --user="root" --password="$params.MYSQL_PASSWORD" < /scripts/create_developer.sql'
                """
                echo "Docker container created: $containerName"

              }
            }
        }
    }

}
