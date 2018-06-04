#! /bin/bash

if [[ -z $CMD ]]; then
    CMD="java -Xms64m -Xmx256m -jar $JAR_FILE"
fi

if [[ -z $APP_NAME ]]; then
    echo 'APP_NAME environment variable must be set when launching the container. This is needed to build the client SSL certificate keystore.'
fi

export INSTANCE_HOST_NAME=$(hostname)

if [[ $VAULT_TOKEN_FILE ]]; then
    VAULT_TOKEN=$(cat $VAULT_TOKEN_FILE)
fi

# If VAULT_TOKEN is set then run under envconsul to provide secrets in env vars to the process
if [[ $VAULT_TOKEN && $VAULT_ADDR ]]; then
    #Install the Vault CA certificate
    mkdir /usr/local/share/ca-certificates/ascent
    echo "Downloading Vault CA certificate from $VAULT_ADDR/v1/pki/ca/pem"
    curl -L -s --insecure $VAULT_ADDR/v1/pki/ca/pem > /usr/local/share/ca-certificates/ascent/vault-ca.crt
    echo 'Update CAs'
    update-ca-certificates
    keytool -importcert -alias vault -keystore $JAVA_HOME/jre/lib/security/cacerts -noprompt -storepass changeit -file /usr/local/share/ca-certificates/ascent/vault-ca.crt
    
    #Build the trusted keystore
    CA_CERTS=$(curl -L -s --insecure -X LIST -H "X-Vault-Token: $VAULT_TOKEN" $VAULT_ADDR/v1/secret/ssl/trusted | jq -r '.data.keys[]')
    for cert in $CA_CERTS; do
        curl -L -s --insecure -X GET -H "X-Vault-Token: $VAULT_TOKEN" $VAULT_ADDR/v1/secret/ssl/trusted/$cert | jq -r '.data.certificate' > $TMPDIR/$cert.crt
        keytool -importcert -alias $cert -keystore $JAVA_HOME/jre/lib/security/cacerts -noprompt -storepass changeit -file $TMPDIR/$cert.crt
    done

    #Build the client keystore
    CLIENT_KEYSTORE_PASS='changeit'
    keytool -genkey -alias app -keystore $JAVA_HOME/jre/lib/security/client.jks -storepass $CLIENT_KEYSTORE_PASS
    keytool -delete -alias app -keystore $JAVA_HOME/jre/lib/security/client.jks -storepass $CLIENT_KEYSTORE_PASS
    CLIENT_CERTS=$(curl -L -s --insecure -X LIST -H "X-Vault-Token: $VAULT_TOKEN" $VAULT_ADDR/v1/secret/ssl/client/$APP_NAME | jq -r '.data.keys[]')
    for cert in $CLIENT_CERTS; do
        curl -L -s --insecure -X GET -H "X-Vault-Token: $VAULT_TOKEN" $VAULT_ADDR/v1/secret/ssl/client/$APP_NAME/$cert | jq -r '.data.certificate' > $TMPDIR/$APP_NAME-$cert.crt
        curl -L -s --insecure -X GET -H "X-Vault-Token: $VAULT_TOKEN" $VAULT_ADDR/v1/secret/ssl/client/$APP_NAME/$cert | jq -r '.data.private_key' > $TMPDIR/$APP_NAME-$cert.key
        
        openssl pkcs12 -export -out $TMPDIR/$APP_NAME-$cert.p12 -inkey $TMPDIR/$APP_NAME-$cert.key -in $TMPDIR/$APP_NAME-$cert.crt
        keytool -importkeystore -srckeystore $TMPDIR/$APP_NAME-$cert.p12 -srcstoretype PKCS12 -destkeystore $JAVA_HOME/jre/lib/security/client.jks -deststoretype JKS -destkeypass $CLIENT_KEYSTORE_PASS
    done

    #Launch the app in another shell to keep secrets secure
    envconsul -config="$ENVCONSUL_CONFIG" -vault-addr=$VAULT_ADDR $CMD
else
    $CMD
fi