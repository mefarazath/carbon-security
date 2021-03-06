/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.security.caas.user.core.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.security.caas.api.CarbonCallback;
import org.wso2.carbon.security.caas.internal.CarbonSecurityDataHolder;
import org.wso2.carbon.security.caas.user.core.bean.User;
import org.wso2.carbon.security.caas.user.core.config.CredentialStoreConfig;
import org.wso2.carbon.security.caas.user.core.constant.UserCoreConstants;
import org.wso2.carbon.security.caas.user.core.context.AuthenticationContext;
import org.wso2.carbon.security.caas.user.core.exception.AuthenticationFailure;
import org.wso2.carbon.security.caas.user.core.exception.CredentialStoreException;
import org.wso2.carbon.security.caas.user.core.exception.IdentityStoreException;
import org.wso2.carbon.security.caas.user.core.exception.StoreException;
import org.wso2.carbon.security.caas.user.core.exception.UserNotFoundException;
import org.wso2.carbon.security.caas.user.core.service.RealmService;
import org.wso2.carbon.security.caas.user.core.store.connector.CredentialStoreConnector;
import org.wso2.carbon.security.caas.user.core.store.connector.CredentialStoreConnectorFactory;

import java.util.HashMap;
import java.util.Map;
import javax.security.auth.callback.Callback;

/**
 * Represents a virtual credential store to abstract the underlying stores.
 * @since 1.0.0
 */
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    private RealmService realmService;
    private Map<String, CredentialStoreConnector> credentialStoreConnectors = new HashMap<>();

    /**
     * Initialize credential store.
     * @param realmService Parent RealmService instance.
     * @param credentialStoreConfigs Store configs related to the credential store.
     * @throws CredentialStoreException Credential Store Exception.
     */
    public void init(RealmService realmService, Map<String, CredentialStoreConfig> credentialStoreConfigs)
            throws CredentialStoreException {

        this.realmService = realmService;

        if (credentialStoreConfigs.isEmpty()) {
            throw new StoreException("At least one credential store configuration must present.");
        }

        for (Map.Entry<String, CredentialStoreConfig> credentialStoreConfig : credentialStoreConfigs.entrySet()) {

            String connectorType = credentialStoreConfig.getValue().getConnectorType();
            CredentialStoreConnectorFactory credentialStoreConnectorFactory = CarbonSecurityDataHolder.getInstance()
                    .getCredentialStoreConnectorFactoryMap().get(connectorType);

            if (credentialStoreConnectorFactory == null) {
                throw new StoreException("No credential store connector factory found for given type.");
            }

            CredentialStoreConnector credentialStoreConnector = credentialStoreConnectorFactory.getInstance();
            credentialStoreConnector.init(credentialStoreConfig.getKey(), credentialStoreConfig.getValue());

            credentialStoreConnectors.put(credentialStoreConfig.getKey(), credentialStoreConnector);
        }

        if (log.isDebugEnabled()) {
            log.debug("Credential store successfully initialized.");
        }
    }

    /**
     * Authenticate the user.
     * @param callbacks Callbacks to get the user details.
     * @return If the authentication is success. AuthenticationFailure otherwise.
     * @throws AuthenticationFailure Authentication Failure.
     */
    public AuthenticationContext authenticate(Callback[] callbacks) throws AuthenticationFailure {

        User user;
        try {
            // Get the user using given callbacks. We need to find the user unique id.
            user = realmService.getIdentityStore().getUser(callbacks);

            // Crete a new call back array from existing one and add new user data (user id and identity store id)
            // as a carbon callback to the new array.
            Callback [] newCallbacks = new Callback[callbacks.length + 1];
            System.arraycopy(callbacks, 0, newCallbacks, 0, callbacks.length);

            // User data will be a map.
            CarbonCallback<Map> carbonCallback = new CarbonCallback<>(null);
            Map<String, String> userData = new HashMap<>();
            userData.put(UserCoreConstants.USER_ID, user.getUserId());
            userData.put(UserCoreConstants.IDENTITY_STORE_ID, user.getIdentityStoreId());
            carbonCallback.setContent(userData);

            // New callback always will be the last element.
            newCallbacks[newCallbacks.length - 1] = carbonCallback;

            // Old callbacks with the new carbon callback.
            callbacks = newCallbacks;
        } catch (IdentityStoreException | UserNotFoundException e) {
            throw new AuthenticationFailure("Error occurred while retrieving user.", e);
        }

        AuthenticationFailure authenticationFailure = new AuthenticationFailure("Invalid user credentials.");

        for (CredentialStoreConnector credentialStoreConnector : credentialStoreConnectors.values()) {

            try {
                User.UserBuilder userBuilder = credentialStoreConnector.authenticate(callbacks);

                // If the authentication failed, there should be a authentication failure exception. But we are double
                // checking for the null as well.
                if (userBuilder == null) {
                    throw new AuthenticationFailure("Authentication failed for user. User builder is null.");
                }
                return new AuthenticationContext(user);
            } catch (AuthenticationFailure | CredentialStoreException failure) {
                authenticationFailure.addSuppressed(failure);
            }
        }
        throw authenticationFailure;
    }
}
