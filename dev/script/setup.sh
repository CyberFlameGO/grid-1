#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR=${DIR}/../..

# load values from .env into environment variables
# see https://stackoverflow.com/a/30969768/3868241
set -o allexport
# shellcheck source=../.env
source "$ROOT_DIR/dev/.env"
set +o allexport

# ---- START static variables that shouldn't be changed
export AWS_PAGER=""
export AWS_CBOR_DISABLE=true

LOCALSTACK_ENDPOINT=http://localhost:4566

CORE_STACK_FILE="$ROOT_DIR/dev/cloudformation/grid-dev-core.yml"
CORE_STACK_FILENAME=$(basename "$CORE_STACK_FILE")

AUTH_STACK_FILE="$ROOT_DIR/dev/cloudformation/grid-dev-auth.yml"
AUTH_STACK_FILENAME=$(basename "$AUTH_STACK_FILE")
# ---- END

LOCAL_AUTH=true
for arg in "$@"; do
  if [ "$arg" == "--clean" ]; then
    CLEAN=true
    shift
  fi
  if [ "$arg" == "--without-local-auth" ]; then
    LOCAL_AUTH=false
    shift
  fi
done

clean() {
  if [[ $CLEAN != true ]]; then
    return
  fi

  echo "removing all previous local infrastructure"

  rm -rf "$ROOT_DIR/dev/.localstack"
  echo "  removed historical localstack data"

  docker-compose down -v
  echo "  removed docker containers"

  docker-compose build
  echo "  rebuild docker containers"
}

startDocker() {
  docker-compose up -d

  echo "waiting for localstack to launch on $LOCALSTACK_ENDPOINT"
  while ! curl -s $LOCALSTACK_ENDPOINT >/dev/null; do
    echo "  localstack not ready yet"
    sleep 1 # wait for 1 second before check again
  done
}

setupDevNginx() {
  imageOriginBucket=$(getStackResource "$CORE_STACK_NAME" ImageOriginBucket)
  imagesBucket=$(getStackResource "$CORE_STACK_NAME" ImageBucket)

  target="$ROOT_DIR/dev/nginx-mappings.yml"

  sed -e "s/@IMAGE-ORIGIN-BUCKET/$imageOriginBucket/g" \
    -e "s/@IMAGE-BUCKET/$imagesBucket/g" \
    -e "s/@DOMAIN_ROOT/$DOMAIN/g" \
    "$ROOT_DIR/dev/nginx-mappings.yml.template" > "$target"

  dev-nginx setup-app "$target"
}

setupPanDomainConfiguration() {
  if [[ $LOCAL_AUTH != true ]]; then
    return
  fi

  echo "setting up pan domain authentication configuration for local auth"

  panDomainBucket=$(getStackResource "$AUTH_STACK_NAME" PanDomainBucket)

  PANDA_COOKIE_NAME=gutoolsAuth-assym

  OUTPUT_DIR=/tmp

  PANDA_PRIVATE_SETTINGS_FILE="$OUTPUT_DIR/$DOMAIN.settings"
  PANDA_PUBLIC_SETTINGS_FILE="$OUTPUT_DIR/$DOMAIN.settings.public"

  PRIVATE_KEY_FILE=$(mktemp "$OUTPUT_DIR/private-key.XXXXXX")
  PUBLIC_KEY_FILE=$(mktemp "$OUTPUT_DIR/public-key.XXXXXX")

  openssl genrsa -out "$PRIVATE_KEY_FILE" 4096
  openssl rsa -pubout -in "$PRIVATE_KEY_FILE" -out "$PUBLIC_KEY_FILE"

  privateKey=$(sed -e '1d' -e '$d' < "$PRIVATE_KEY_FILE" | tr -d '\n')
  publicKey=$(sed -e '1d' -e '$d'  < "$PUBLIC_KEY_FILE" | tr -d '\n')

  privateSettings=$(cat <<END
privateKey=${privateKey}
publicKey=${publicKey}
cookieName=${PANDA_COOKIE_NAME}
clientId=${OIDC_CLIENT_ID}
clientSecret=${OIDC_CLIENT_SECRET}
discoveryDocumentUrl=http://localhost:9014/.well-known/openid-configuration
END
)

  publicSettings=$(cat <<END
publicKey=${publicKey}
END
)

  echo "$privateSettings" > "$PANDA_PRIVATE_SETTINGS_FILE"
  echo "$publicSettings" > "$PANDA_PUBLIC_SETTINGS_FILE"

  filesToUpload=(
    "$PANDA_PRIVATE_SETTINGS_FILE"
    "$PANDA_PUBLIC_SETTINGS_FILE"
  )

  for file in "${filesToUpload[@]}"; do
    aws s3 cp "$file" "s3://$panDomainBucket/" --endpoint-url $LOCALSTACK_ENDPOINT
    echo "  uploaded $file to bucket $panDomainBucket"
  done

  rm -f "$PUBLIC_KEY_FILE"
  rm -f "$PRIVATE_KEY_FILE"
  rm -f "$PANDA_PRIVATE_SETTINGS_FILE"
  rm -f "$PANDA_PUBLIC_SETTINGS_FILE"
}

setupPermissionConfiguration() {
  if [[ $LOCAL_AUTH != true ]]; then
    return
  fi

  echo "setting up permissions configuration for local auth"

  permissionsBucket=$(getStackResource "$AUTH_STACK_NAME" PermissionsBucket)

  aws s3 cp "$ROOT_DIR/dev/config/permissions.json" \
    "s3://$permissionsBucket/" \
    --endpoint-url $LOCALSTACK_ENDPOINT

  echo "  uploaded file to $permissionsBucket"
}

getStackResource() {
  stackName=$1
  resourceName=$2

  stackResources=$(
    aws cloudformation describe-stack-resources \
      --stack-name "$stackName" \
      --endpoint-url $LOCALSTACK_ENDPOINT
  )

  resource=$(
    echo "$stackResources" \
    | jq -r ".StackResources[] | select(.LogicalResourceId == \"$resourceName\") | .PhysicalResourceId"
  )

  echo "$resource"
}

createCoreStack() {
  echo "creating local core cloudformation stack"

  aws cloudformation create-stack \
    --stack-name "$CORE_STACK_NAME" \
    --template-body "file://$CORE_STACK_FILE" \
    --endpoint-url $LOCALSTACK_ENDPOINT > /dev/null
  echo "  created stack $CORE_STACK_NAME using $CORE_STACK_FILENAME"
}

createLocalAuthStack() {
  if [[ $LOCAL_AUTH != true ]]; then
    return
  fi

  echo "creating local auth cloudformation stack"

  aws cloudformation create-stack \
    --stack-name "$AUTH_STACK_NAME" \
    --template-body "file://$AUTH_STACK_FILE" \
    --endpoint-url $LOCALSTACK_ENDPOINT > /dev/null
  echo "  created stack $AUTH_STACK_NAME using $AUTH_STACK_FILENAME"
}

main() {
  clean
  startDocker
  createCoreStack

  if [[ $LOCAL_AUTH == true ]]; then
    createLocalAuthStack
    setupPermissionConfiguration
    setupPanDomainConfiguration
  fi

  setupDevNginx
}

main
