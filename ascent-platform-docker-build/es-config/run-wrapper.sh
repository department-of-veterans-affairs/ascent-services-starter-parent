#!/bin/bash

CONSUL_TEMPLATE_CONFIG="/usr/share/elasticsearch/template/consul-template-config.hcl"
ENVCONSUL_CONFIG="/usr/share/elasticsearch/template/envconsul-config.hcl"
ES_BASE_URL=elastic.internal.vets-api.gov:9200
default_pass=changeme

if [[ -s $VAULT_TOKEN_FILE ]]; then
    echo "vault token file found and not empty."
    VAULT_TOKEN=$(cat $VAULT_TOKEN_FILE)
fi

if [[ -z $VAULT_ADDR ]]; then
    VAULT_ADDR="http://vault:8200"
fi

if [[ $VAULT_TOKEN ]]; then
    consul-template -once -config="$CONSUL_TEMPLATE_CONFIG" -vault-addr="$VAULT_ADDR" -vault-token="$VAULT_TOKEN"
    ES_BASE_URL="--cacert /usr/share/elasticsearch/config/ca.pem  https://elastic.internal.vets-api.gov:9200"
fi

echo "using url $ES_URL"

echo "--- Polling to wait for elasticsearch to be up"
until $(curl -XGET --output /dev/null --silent --head --fail -u elastic:changeme ${ES_BASE_URL}/_cat/health); do
    sleep 10
    echo "trying again"
done

echo "--- Polling to wait for elasticsearch to be ready to configure"
curl -XGET -u elastic:$default_pass $ES_BASE_URL/_cluster/health?wait_for_status=green


# If ENVCONSUL_CONFIG is set then run under envconsul to provide secrets in env vars to the process
if [[ $VAULT_TOKEN ]]; then
    # Create first password using vault
    envconsul -config="$ENVCONSUL_CONFIG" -vault-addr="$VAULT_ADDR" -vault-token="$VAULT_TOKEN" /docker/changepass.sh
    envconsul -config="$ENVCONSUL_CONFIG" -vault-addr="$VAULT_ADDR" -vault-token="$VAULT_TOKEN" /docker/set-replicas.sh
else
    /docker/changepass.sh
    /docker/set-replicas.sh
fi

