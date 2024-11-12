/*
 * Copyright © 2021-2023 moehreag <moehreag@gmail.com> & Contributors
 *
 * This file is part of AxolotlClient.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 * For more information, see the LICENSE file.
 */

package io.github.axolotlclient.api;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.github.mizosoft.methanol.Methanol;
import io.github.axolotlclient.api.handlers.ChatHandler;
import io.github.axolotlclient.api.handlers.FriendRequestHandler;
import io.github.axolotlclient.api.handlers.FriendRequestReactionHandler;
import io.github.axolotlclient.api.handlers.StatusUpdateHandler;
import io.github.axolotlclient.api.requests.AccountSettingsRequest;
import io.github.axolotlclient.api.types.AccountSettings;
import io.github.axolotlclient.api.types.PkSystem;
import io.github.axolotlclient.api.types.Status;
import io.github.axolotlclient.api.types.User;
import io.github.axolotlclient.api.util.MojangAuth;
import io.github.axolotlclient.api.util.SocketMessageHandler;
import io.github.axolotlclient.api.util.StatusUpdateProvider;
import io.github.axolotlclient.api.util.TimestampParser;
import io.github.axolotlclient.modules.auth.Account;
import io.github.axolotlclient.util.GsonHelper;
import io.github.axolotlclient.util.Logger;
import io.github.axolotlclient.util.NetworkUtil;
import io.github.axolotlclient.util.ThreadExecuter;
import io.github.axolotlclient.util.notifications.NotificationProvider;
import io.github.axolotlclient.util.translation.TranslationProvider;
import lombok.Getter;
import lombok.Setter;

public class API {

	@Getter
	private static API Instance;
	@Getter
	private final Logger logger;
	@Getter
	private final NotificationProvider notificationProvider;
	@Getter
	private final TranslationProvider translationProvider;
	private final StatusUpdateProvider statusUpdateProvider;
	@Getter
	private final Options apiOptions;
	private final Collection<SocketMessageHandler> handlers;
	private WebSocket socket;
	@Getter
	private String uuid;
	@Getter
	private User self;
	private Account account;
	private String token;
	@Getter
	@Setter
	private AccountSettings settings;
	private HttpClient client;

	public API(Logger logger, NotificationProvider notificationProvider, TranslationProvider translationProvider,
			   StatusUpdateProvider statusUpdateProvider, Options apiOptions) {
		if (Instance != null) {
			throw new IllegalStateException("API may only be instantiated once!");
		}
		this.logger = logger;
		this.notificationProvider = notificationProvider;
		this.translationProvider = translationProvider;
		this.statusUpdateProvider = statusUpdateProvider;
		this.apiOptions = apiOptions;
		handlers = new HashSet<>();
		handlers.add(ChatHandler.getInstance());
		handlers.add(new FriendRequestHandler());
		handlers.add(new FriendRequestReactionHandler());
		handlers.add(new StatusUpdateHandler());
		Instance = this;
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
	}


	public void onOpen(WebSocket channel) {
		this.socket = channel;
		logger.debug("API connected!");
	}

	private void authenticate() {
		//if (client != null) {
		// We have to rely on the gc to collect previous client objects as close() was only implemented in java 21.
		// However, we are currently compiling against java 17.
		//client.close();
		//}
		logDetailed("Authenticating with Mojang...");

		MojangAuth.Result result = MojangAuth.authenticate(account);

		if (result.getStatus() != MojangAuth.Status.SUCCESS) {
			logger.error("Failed to authenticate with Mojang! Status: ", result.getStatus());
			return;
		}

		logDetailed("Requesting authentication from backend...");

		get(Request.Route.AUTHENTICATE.builder()
			.query("username", account.getName())
			.query("server_id", result.getServerId())
			.build()).whenComplete((response, throwable) -> {

			if (throwable != null) {
				logger.error("Failed to authenticate!", throwable);
				return;
			}
			if (response.isError()) {
				logger.error("Failed to authenticate!", response.getError().description());
				return;
			}

			token = response.getBody("access_token");
			logDetailed("Obtained token!");
			CompletableFuture.allOf(get(Request.Route.ACCOUNT.builder().build())
					.thenAccept(r -> {
						self = new User(sanitizeUUID(r.getBody("uuid")),
							r.getBody("username"), "self",
							r.getBody("registered", TimestampParser::parse),
							Status.UNKNOWN,
							r.ifBodyHas("previous_usernames", () -> {
								List<Map<?, ?>> previous = r.getBody("previous_usernames");
								return previous.stream().map(m -> new User.OldUsername((String) m.get("username"), (boolean) m.get("public")))
									.collect(Collectors.toList());
							}));
						self.setSystem(PkSystem.fromToken(apiOptions.pkToken.get()).join());
						logDetailed("Created self user!");
					}),
				AccountSettingsRequest.get().thenAccept(r -> {
					apiOptions.retainUsernames.set(r.isRetainUsernames());
					apiOptions.showActivity.set(r.isShowActivity());
					apiOptions.showLastOnline.set(r.isShowLastOnline());
					apiOptions.showRegistered.set(r.isShowRegistered());
				})).thenRun(() -> logDetailed("completed data requests")).join();
			createSession();
			startStatusUpdateThread();
		});
	}

	public CompletableFuture<Response> get(Request request) {
		return request(request, "GET");
	}

	public CompletableFuture<Response> patch(Request request) {
		return request(request, "PATCH");
	}

	public CompletableFuture<Response> post(Request request) {
		return request(request, "POST");
	}

	public CompletableFuture<Response> delete(Request request) {
		return request(request, "DELETE");
	}

	private CompletableFuture<Response> request(Request request, String method) {
		if (request.requiresAuthentication() && !isAuthenticated()) {
			logger.warn("Tried to request {} {} without authentication, but this request requires it!", method, request);
			return CompletableFuture.failedFuture(new Throwable("Not Authenticated"));
		}
		URI route = getUrl(request);
		return request(route, request.bodyFields(), request.rawBody(), method, request.headers());
	}

	private CompletableFuture<Response> request(URI url, Map<String, ?> payload, byte[] rawBody, String method, Map<String, String> headers) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				logDetailed("Starting request to " + method + " " + url);

				HttpRequest.Builder builder = HttpRequest.newBuilder(url)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json");

				if (token != null) {
					builder.header("Authorization", token);
				}

				if (headers != null) {
					headers.forEach(builder::header);
				}

				if (rawBody != null) {
					builder.method(method, HttpRequest.BodyPublishers.ofByteArray(rawBody));
				} else if (!(payload == null || payload.isEmpty())) {
					StringBuilder body = new StringBuilder();
					GsonHelper.GSON.toJson(payload, body);
					logDetailed("Sending payload: \n" + body);
					builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
				} else {
					builder.method(method, HttpRequest.BodyPublishers.noBody());
				}
				if (client == null) {
					client = NetworkUtil.createHttpClient("API");
				}

				HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

				String body = response.body();

				int code = response.statusCode();
				logDetailed("Response: code: " + code + " body: " + body);
				return Response.builder().body(body).status(code).headers(response.headers().map()).build();
			} catch (ConnectException e) {
				logger.warn("Backend unreachable!");
				return Response.CLIENT_ERROR;
			} catch (Exception e) {
				onError(e);
				return Response.CLIENT_ERROR;
			}
		});
	}

	URI getUrl(Request request) {
		StringBuilder url = new StringBuilder(Constants.API_URL.endsWith("/") ? Constants.API_URL : Constants.API_URL + "/");
		url.append(request.route().getPath());
		if (request.path() != null) {
			for (String p : request.path()) {
				url.append("/").append(p);
			}
		}
		if (request.query() != null && !request.query().isEmpty()) {
			url.append("?");
			request.query().forEach((v) -> {
				if (url.charAt(url.length() - 1) != '?') {
					url.append("&");
				}
				url.append(v);
			});
		}
		return URI.create(url.toString());
	}

	public void shutdown() {
		if (isAuthenticated()) {
			logger.debug("Shutting down API");
			if (isSocketConnected()) {
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutdown");
				socket = null;
			}
			// We have to rely on the gc to collect previous client objects as close() was only implemented in java 21.
			// However, we are currently compiling against java 17.
			//client.close();
			client = null;
		}
	}

	public boolean isSocketConnected() {
		return socket != null && !socket.isInputClosed();
	}

	public boolean isAuthenticated() {
		return token != null;
	}

	public void logDetailed(String message, Object... args) {
		if (apiOptions.detailedLogging.get()) {
			logger.debug("[DETAIL] " + message, args);
		}
	}

	public void onMessage(String message) {
		logDetailed("Handling socket message: {}", message);

		Response res = Response.builder().status(200).body(message).build();
		for (SocketMessageHandler handler : handlers) {
			if (handler.isApplicable(res.getBody("target"))) {
				handler.handle(res);
				break;
			}
		}
	}

	public void onError(Throwable throwable) {
		logger.error("Error while handling API traffic:", throwable);
	}

	public void onClose(int statusCode, String reason) {
		logDetailed("Session closed! code: " + statusCode + " reason: " + reason);
		if (apiOptions.enabled.get()) {
			logDetailed("Restarting API session...");
			startup(account);
			logDetailed("Restarted API session!");
		}
	}

	private void createSession() {
		if (!Constants.TESTING) {
			try {
				logDetailed("Connecting to websocket..");
				socket = ((Methanol) client).underlyingClient().newWebSocketBuilder().header("Authorization", token)
					.buildAsync(URI.create(Constants.SOCKET_URL), new ClientEndpoint()).get();
				logDetailed("Socket connected");
			} catch (Exception e) {
				logger.error("Failed to start Socket! ", e);
			}
		}
	}

	public void restart() {
		if (isSocketConnected()) {
			shutdown();
		}
		if (account != null) {
			startup(account);
		} else {
			apiOptions.enabled.set(false);
		}
	}

	public void startup(Account account) {
		this.uuid = account.getUuid();
		this.account = account;
		if (!Constants.ENABLED) {
			return;
		}

		if (account.isOffline()) {
			return;
		}

		ThreadExecuter.scheduleTask(() -> {
			if (apiOptions.enabled.get()) {
				switch (apiOptions.privacyAccepted.get()) {
					case "unset":
						apiOptions.openPrivacyNoteScreen.accept(v -> {
							if (v) startupAPI();
						});
						break;
					case "accepted":
						startupAPI();
						break;
					default:
						break;
				}
			}
		});
	}

	void startupAPI() {
		if (!isSocketConnected()) {

			if (Constants.TESTING) {
				return;
			}
			logger.debug("Starting API...");
			ThreadExecuter.scheduleTask(this::authenticate);
		} else {
			logger.warn("API is already running!");
		}
	}

	private void startStatusUpdateThread() {
		statusUpdateProvider.initialize();
		new Thread("Status Update Thread") {
			@Override
			public void run() {
				try {
					Thread.sleep(50);
				} catch (InterruptedException ignored) {
				}
				while (API.getInstance().isSocketConnected()) {
					Request request = statusUpdateProvider.getStatus();
					if (request != null) {
						post(request);
					}
					try {
						//noinspection BusyWait
						Thread.sleep(Constants.STATUS_UPDATE_DELAY * 1000);
					} catch (InterruptedException ignored) {

					}
				}
			}
		}.start();
	}

	public String sanitizeUUID(String uuid) {
		if (uuid.contains("-")) {
			return validateUUID(uuid.replace("-", ""));
		}
		return validateUUID(uuid);
	}

	private String validateUUID(String uuid) {
		if (uuid.length() != 32) {
			throw new IllegalArgumentException("Not a valid UUID (undashed): " + uuid);
		}
		return uuid;
	}
}
