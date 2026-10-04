// CI for the sentiment vertical plugin. Builds the Jersey API component jar (maven, against the
// PUBLISHED webrobot-jersey-plugin-sdk on jitpack) AND the ETL component jar (Scala/Gradle, SDK from
// jitpack), archiving both. The CLI jar is a rarely-rebuilt artifact committed under
// build/bundle-stage/. The multi-component bundle is assembled by scripts/package-bundle.sh and
// installed via the platform's /admin/bundles/install flow (see repo README); this job's artifacts
// (api + etl jars) drop into that bundle.
//
// Kept deliberately build-only: the bundle install + approve (super_admin, scan gate) stays a
// deliberate operator step, not an unattended CI deploy.
pipeline {
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  imagePullSecrets:
  - name: docker-config-secret
  containers:
  - name: maven
    image: maven:3.9.11-amazoncorretto-17
    command: ["sleep"]
    args: ["infinity"]
    tty: true
    resources:
      requests: { cpu: "500m", memory: "1Gi" }
      limits:   { cpu: "2",    memory: "2Gi" }
  - name: gradle
    image: gradle:8.10.2-jdk17
    command: ["sleep"]
    args: ["infinity"]
    tty: true
    resources:
      requests: { cpu: "500m", memory: "1Gi" }
      limits:   { cpu: "2",    memory: "2Gi" }
'''
        }
    }

    options {
        timeout(time: 20, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {
        stage('Build api jar') {
            steps {
                container('maven') {
                    sh '''
                        set -e
                        echo "📦 Building webrobot-sentimental-plugin api jar (against published SDK via jitpack)..."
                        cd api
                        mvn -B -ntp -DskipTests clean package
                        echo "--- artifacts ---"
                        ls -lh target/*.jar
                    '''
                }
            }
        }
        stage('Build etl jar') {
            steps {
                container('gradle') {
                    sh '''
                        set -e
                        echo "📦 Building webrobot-sentimental-plugin etl (Scala) jar (SDK from jitpack)..."
                        # settings.gradle.kts includes etl + api; only :etl is a gradle project.
                        gradle --no-daemon -g "$WORKSPACE/.gradle" :etl:jar -x test
                        echo "--- artifacts ---"
                        ls -lh etl/build/libs/*.jar
                    '''
                }
            }
        }
    }

    post {
        success { echo "✅ api + etl jars built — download from build artifacts, drop into the bundle (scripts/package-bundle.sh), install via /admin/bundles/install" }
        always  { archiveArtifacts artifacts: 'api/target/*.jar,etl/build/libs/*.jar', allowEmptyArchive: true, fingerprint: true }
    }
}
