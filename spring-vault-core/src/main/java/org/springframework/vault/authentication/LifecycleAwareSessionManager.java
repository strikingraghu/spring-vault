/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.authentication;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;
import org.springframework.util.NumberUtils;
import org.springframework.util.StringUtils;
import org.springframework.vault.client.VaultClient;
import org.springframework.vault.client.VaultException;
import org.springframework.vault.client.VaultResponseEntity;
import org.springframework.vault.support.VaultToken;

/**
 * Lifecycle-aware Session Manager. This {@link SessionManager} obtains tokens from a {@link ClientAuthentication} upon
 * {@link #getSessionToken() request}. Tokens are renewed asynchronously if a token has a lease duration. This happens 5
 * seconds before the token expires, see {@link #REFRESH_PERIOD_BEFORE_EXPIRY}.
 * <p>
 * This {@link SessionManager} also implements {@link DisposableBean} to revoke the {@link LoginToken} once it's not
 * required anymore. Token revocation will stop regular token refresh.
 * <p>
 * If Token renewal runs into a client-side error, it assumes the token was revoked/expired and discards the token state
 * so the next attempt will lead to another login attempt.
 *
 * @author Mark Paluch
 * @see LoginToken
 * @see SessionManager
 * @see AsyncTaskExecutor
 */
public class LifecycleAwareSessionManager implements SessionManager, DisposableBean {

	private final static Logger logger = LoggerFactory.getLogger(LifecycleAwareSessionManager.class);
	public static final int REFRESH_PERIOD_BEFORE_EXPIRY = 5;

	private final ClientAuthentication clientAuthentication;
	private final VaultClient vaultClient;
	private final AsyncTaskExecutor taskExecutor;
	private final Object lock = new Object();

	private volatile VaultToken token;

	/**
	 * Create a {@link LifecycleAwareSessionManager} given {@link ClientAuthentication}, {@link AsyncTaskExecutor} and
	 * {@link VaultClient}.
	 * 
	 * @param clientAuthentication must not be {@literal null}.
	 * @param taskExecutor must not be {@literal null}.
	 * @param vaultClient must not be {@literal null}.
	 */
	public LifecycleAwareSessionManager(ClientAuthentication clientAuthentication, AsyncTaskExecutor taskExecutor,
			VaultClient vaultClient) {

		Assert.notNull(clientAuthentication, "ClientAuthentication must not be null");
		Assert.notNull(taskExecutor, "AsyncTaskExecutor must not be null");
		Assert.notNull(vaultClient, "VaultClient must not be null");

		this.clientAuthentication = clientAuthentication;
		this.vaultClient = vaultClient;
		this.taskExecutor = taskExecutor;
	}

	@Override
	public void destroy() {

		VaultToken token = this.token;
		this.token = null;

		if (token instanceof LoginToken) {
			VaultResponseEntity<Map> response = vaultClient.postForEntity("auth/token/revoke-self", token, null, Map.class);

			if (!response.isSuccessful()) {
				logger.warn("Cannot revoke VaultToken: {}", buildExceptionMessage(response));
			}
		}
	}

	/**
	 * Performs a token refresh. Creates a new token if no token was obtained before. If a token was obtained before, it
	 * uses self-renewal to renew the current token. Client-side errors (like permission denied) indicate the token cannot
	 * be renewed because it's expired or simply not found.
	 * 
	 * @return {@literal true} if the refresh was successful. {@literal false} if a new token was obtained or refresh
	 *         failed.
	 */
	protected boolean renewToken() {

		if (token == null) {
			getSessionToken();
			return false;
		}

		VaultResponseEntity<Map> response = vaultClient.postForEntity("auth/token/renew-self", token, null, Map.class);

		if (!response.isSuccessful()) {

			if (response.getStatusCode().is4xxClientError()) {
				logger.debug("Cannot refresh token, resetting token and performing re-login: {}",
						buildExceptionMessage(response));
				token = null;
				return false;
			}

			throw new VaultException(buildExceptionMessage(response));
		}

		return true;
	}

	@Override
	public VaultToken getSessionToken() {

		if (token == null) {

			synchronized (lock) {

				if (token == null) {
					token = clientAuthentication.login();

					if (isTokenRenewable()) {
						scheduleRefresh();
					}
				}
			}
		}

		return token;
	}

	private boolean isTokenRenewable() {

		if (token instanceof LoginToken) {

			LoginToken loginToken = (LoginToken) token;
			return loginToken.getLeaseDuration() > 0 && loginToken.isRenewable();
		}

		return false;
	}

	private void scheduleRefresh() {

		logger.debug("Refreshing token");

		LoginToken loginToken = (LoginToken) token;
		final int seconds = NumberUtils.convertNumberToTargetClass(
				Math.max(1, loginToken.getLeaseDuration() - REFRESH_PERIOD_BEFORE_EXPIRY), Integer.class);

		final Runnable task = new Runnable() {
			@Override
			public void run() {
				try {
					if (LifecycleAwareSessionManager.this.token != null && isTokenRenewable()) {
						if (renewToken()) {
							scheduleRefresh();
						}
					}
				} catch (Exception e) {
					logger.error("Cannot refresh VaultToken", e);
				}
			}
		};

		if (taskExecutor instanceof TaskScheduler) {

			TaskScheduler taskScheduler = (TaskScheduler) taskExecutor;
			taskScheduler.scheduleWithFixedDelay(task, TimeUnit.SECONDS.toMillis(seconds));
			return;
		}

		taskExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
					task.run();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private static String buildExceptionMessage(VaultResponseEntity<?> response) {

		if (StringUtils.hasText(response.getMessage())) {
			return String.format("Status %s URI %s: %s", response.getStatusCode(), response.getUri(), response.getMessage());
		}

		return String.format("Status %s URI %s", response.getStatusCode(), response.getUri());
	}
}