#!/usr/bin/env bash

set -e

SCRIPT_DIR=$(dirname ${0})

setupNvm() {
  export NVM_DIR="$HOME/.nvm"
  if [ -s "$NVM_DIR/nvm.sh" ]; then
    # This loads nvm if installed directly
    source "$NVM_DIR/nvm.sh"
  elif [ -s "$(brew --prefix nvm)/nvm.sh" ]; then
    # This will load nvm if installed via brew
    source "$(brew --prefix nvm)/nvm.sh"
  else
    echo "Can't find NVM"
    exit 1
  fi

  nvm install
  nvm use
}

buildKahuna() {
  echo "##teamcity[compilationStarted compiler='webpack']"

  pushd ${SCRIPT_DIR}/../../kahuna

  # clear old packages first
  rm -rf node_modules

  npm install
  npm run undist
  npm test
  npm run dist
  echo "##teamcity[compilationFinished compiler='webpack']"
  popd
}

buildWatcher() {
  echo "##teamcity[compilationStarted compiler='ncc']"
  pushd ${SCRIPT_DIR}/../../s3watcher/lambda

  nvm use ${NODE_VERSION}
  npm install
  npm test
  npm run build

  popd
  echo "##teamcity[compilationFinished compiler='ncc']"
}

buildSbt() {
  echo "##teamcity[compilationStarted compiler='sbt']"
  # We run our tests in docker to provide dependencies that
  # aren't natively available in CI
  echo "Running tests in Docker"
  docker-compose -f docker-compose.tests.yml run test-container
  echo "Tests complete"
  sbt clean scripts/compile riffRaffUpload
  echo "##teamcity[compilationFinished compiler='sbt']"
}

setupNvm
buildKahuna
buildWatcher
buildSbt
