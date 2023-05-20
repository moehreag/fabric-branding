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

import io.github.axolotlclient.api.handlers.*;
import io.github.axolotlclient.api.types.Status;
import io.github.axolotlclient.api.types.User;
import io.github.axolotlclient.api.util.RequestHandler;
import io.github.axolotlclient.api.util.StatusUpdateProvider;
import io.github.axolotlclient.util.Logger;
import io.github.axolotlclient.util.ThreadExecuter;
import io.github.axolotlclient.util.notifications.NotificationProvider;
import io.github.axolotlclient.util.translation.TranslationProvider;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class API {

	@Getter
	private static API Instance;
	private final HashMap<Integer, Request> requests = new HashMap<>();
	private final Set<RequestHandler> handlers = new HashSet<>();
	@Getter
	private final Logger logger;
	@Getter
	private final NotificationProvider notificationProvider;
	@Getter
	private final TranslationProvider translationProvider;
	private final StatusUpdateProvider statusUpdateProvider;
	@Getter
	private final Options apiOptions;
	private Channel channel;
	@Getter
	private String uuid;
	@Getter
	private User self;

	public API(Logger logger, NotificationProvider notificationProvider, TranslationProvider translationProvider,
			   StatusUpdateProvider statusUpdateProvider, Options apiOptions) {
		this.logger = logger;
		this.notificationProvider = notificationProvider;
		this.translationProvider = translationProvider;
		this.statusUpdateProvider = statusUpdateProvider;
		this.apiOptions = apiOptions;
		Instance = this;
		addHandler(new FriendRequestHandler());
		addHandler(new FriendRequestReactionHandler());
		addHandler(FriendHandler.getInstance());
		addHandler(new StatusUpdateHandler());
		addHandler(ChatHandler.getInstance());
	}

	public void addHandler(RequestHandler handler) {
		handlers.add(handler);
	}

	public void onOpen(Channel channel) {
		this.channel = channel;
		logger.debug("API connected!");
		sendHandshake(uuid);
	}

	private void sendHandshake(String uuid) {
		logger.debug("Starting Handshake");
		Request request = new Request(Request.Type.HANDSHAKE, object -> {
			if (requestFailed(object)) {
				logger.error("Handshake failed, closing API!");
				if (apiOptions.detailedLogging.get()) {
					notificationProvider.addStatus("api.error.handshake", APIError.fromResponse(object));
				}
				shutdown();
			} else {
				logger.debug("Handshake successful!");
				if (apiOptions.detailedLogging.get()) {
					notificationProvider.addStatus("api.success.handshake", "api.success.handshake.desc");
				}
			}
		}, "uuid", uuid);
		send(request);
	}

	public boolean requestFailed(ByteBuf object) {
		try {
			return object.getByte(0x03) == Request.Type.ERROR.getType();
		} catch (IndexOutOfBoundsException e) {
			return true;
		}
	}

	public void shutdown() {
		if (channel != null && channel.isOpen()) {
			ClientEndpoint.shutdown();
			channel = null;
		}
	}

	public void send(Request request) {
		if (isConnected()) {
			if (!Constants.TESTING) {
				requests.put(request.getId(), request);
				ThreadExecuter.scheduleTask(() -> {
					ByteBuf buf = request.getData();
					if (apiOptions.detailedLogging.get()) {
						logDetailed("Sending Request: " + buf.toString(StandardCharsets.UTF_8));
					}
					channel.writeAndFlush(buf);
					buf.release();
				});
			}
		} else {
			logger.warn("Not sending request because API is closed: " + request);
		}
	}

	public boolean isConnected() {
		return channel != null && channel.isOpen();
	}

	public void logDetailed(String message, Object... args) {
		if (apiOptions.detailedLogging.get()) {
			logger.debug("[DETAIL] " + message, args);
		}
	}

	public void onMessage(ByteBuf message) {
		if (apiOptions.detailedLogging.get()) {
			logDetailed("Handling response: " + message.toString(StandardCharsets.UTF_8));
		}
		handleResponse(message);
	}

	private void handleResponse(ByteBuf response) {
		try {
			Integer id = null;
			try {
				id = response.getInt(0x05);
			} catch (IndexOutOfBoundsException ignored) {
			}

			if (requests.containsKey(id)) {
				requests.get(id).getHandler().accept(response);
				requests.remove(id);
			} else if (id == null || id == 0) {
				int type = response.getByte(0x03);
				handlers.stream().filter(handler -> handler.isApplicable(type)).forEach(handler ->
					handler.handle(response));
			} else {
				logger.error("Unknown response: " + response.toString(StandardCharsets.UTF_8));
			}

		} catch (RuntimeException e) {
			e.printStackTrace();
			logger.error("Invalid response: " + response);
		}

		response.release();
	}

	public void onError(Throwable throwable) {
		logger.error("Error while handling API traffic:", throwable);
	}

	public void onClose() {
		logDetailed("Session closed!");
		logDetailed("Restarting API session...");
		createSession();
		logDetailed("Restarted API session!");
	}

	private void createSession() {
		new ClientEndpoint().run(Constants.API_URL, Constants.PORT);
	}

	public void restart() {
		if (isConnected()) {
			shutdown();
		}
		if (uuid != null) {
			startup(uuid);
		} else {
			apiOptions.enabled.set(false);
		}
	}

	public void startup(String uuid) {
		this.uuid = uuid;
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
	}

	void startupAPI() {
		if (!isConnected()) {
			self = new User(this.uuid, Status.UNKNOWN);
			logger.debug("Starting API...");
			createSession();

			while (channel == null) {
				try {
					//noinspection BusyWait
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}

			new Thread("Status Update Thread") {
				@Override
				public void run() {
					try {
						Thread.sleep(50);
					} catch (InterruptedException ignored) {
					}
					while (API.getInstance().isConnected()) {
						Request statusUpdate = statusUpdateProvider.getStatus();
						if (statusUpdate != null) {
							send(statusUpdate);
						}
						try {
							//noinspection BusyWait
							Thread.sleep(Constants.STATUS_UPDATE_DELAY * 1000);
						} catch (InterruptedException ignored) {

						}
					}
				}
			}.start();
		} else {
			logger.warn("API is already running!");
		}
	}

	public String sanitizeUUID(String uuid) {
		if (uuid.contains("-")) {
			return uuid.replace("-", "");
		}
		return uuid;
	}
}
