// CI for the sentiment vertical plugin. Builds the Jersey API component jar (the ETL/CLI jars are
// gradle/maven artifacts rebuilt rarely and committed under build/bundle-stage/); this job compiles
// the thin api jar against the PUBLISHED webrobot-jersey-plugin-sdk (jitpack, public — no token) and
// archives it. The multi-component bundle is assembled by scripts/package-bundle.sh and installed via
// the platform's /admin/bundles/install flow (see repo README); this job's artifact is the api jar
// that drops into that bundle.
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
    }

    post {
        success { echo "✅ api jar built — download from build artifacts, drop into the bundle (scripts/package-bundle.sh), install via /admin/bundles/install" }
        always  { archiveArtifacts artifacts: 'api/target/*.jar', allowEmptyArchive: true, fingerprint: true }
    }
}
