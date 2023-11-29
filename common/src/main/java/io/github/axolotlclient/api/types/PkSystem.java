package io.github.axolotlclient.api.types;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.axolotlclient.api.API;
import io.github.axolotlclient.api.Request;
import io.github.axolotlclient.api.util.BufferUtil;
import io.github.axolotlclient.api.util.Serializer;
import io.github.axolotlclient.util.GsonHelper;
import io.github.axolotlclient.util.NetworkUtil;
import io.github.axolotlclient.util.ThreadExecuter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.util.EntityUtils;

@Data
@AllArgsConstructor
public class PkSystem {
	private static String token;
	@Serializer.Length(5)
	private String id;
	@Serializer.Exclude
	private String name;
	@Serializer.Exclude
	private List<Member> fronters;
	@Serializer.Exclude
	private Member firstFronter;

	private static CompletableFuture<PkSystem> create(String id) {
		return queryPkAPI("systems/" + id).thenApply(object -> getString(object, "name"))
			.thenApply(name -> create(id, name).join());
	}

	private static CompletableFuture<PkSystem> create(String id, String name) {
		return queryPkAPI("systems/" + id + "/fronters").thenApply(object -> {
			JsonArray fronters = object.getAsJsonArray("members");
			List<Member> list = new ArrayList<>();
			fronters.forEach(e ->
				list.add(Member.fromObject(e.getAsJsonObject()))
			);
			return list;
		}).thenApply(list -> new PkSystem(id, name, list, list.get(0)));
	}

	public static CompletableFuture<JsonObject> queryPkAPI(String route) {
		return PluralKitApi.request("https://api.pluralkit.me/v2/" + route);
	}

	public static CompletableFuture<PkSystem> fromToken(String token) {
		PkSystem.token = token;
		if (token.length() != 64) {
			return CompletableFuture.completedFuture(null);
		}
		return queryPkAPI("systems/@me")
			.thenApply(object -> {
				if (object.has("id")) {
					PkSystem system = create(object.get("id").getAsString(), object.get("name").getAsString()).join();
					API.getInstance().getLogger().debug("Logged in as system: " + system.getName());
					return system;
				}
				return null;
			});
	}

	public static CompletableFuture<PkSystem> fromMinecraftUuid(String uuid) {
		return getPkId(uuid).thenApply(pkId -> {
			if (!pkId.isEmpty()) {
				return create(pkId).join();
			}
			return null;
		});
	}

	public static CompletableFuture<String> getPkId(String uuid) {
		return API.getInstance().send(new Request(Request.Type.QUERY_PK_INFO, uuid)).thenApply(buf -> {
			if (buf.readableBytes() > 0x09) {
				return BufferUtil.getString(buf, 0x09, 5);
			}
			return "";
		});
	}

	public Member getProxy(String message) {
		return fronters.stream().filter(m -> m.proxyTags.stream()
			.anyMatch(p -> p.matcher(message).matches())).findFirst().orElse(getFirstFronter());
	}

	@Data
	@AllArgsConstructor
	public static class Member {
		private String id;
		private String displayName;
		private List<Pattern> proxyTags;

		public static Member fromId(String id) {
			return fromObject(queryPkAPI("members/" + id).join());
		}

		public static Member fromObject(JsonObject object) {
			String id = getString(object, "id");
			String name = getString(object, "name");
			JsonArray tags = object.get("proxy_tags").getAsJsonArray();
			List<Pattern> proxyTags = new ArrayList<>();
			tags.forEach(e -> {
				if (e.isJsonObject()) {
					JsonObject o = e.getAsJsonObject();
					String prefix = getString(o, "prefix");
					String suffix = getString(o, "suffix");
					proxyTags.add(Pattern.compile(Pattern.quote(prefix) + ".*" + Pattern.quote(suffix)));
				}
			});
			return new Member(id, name, proxyTags);
		}
	}

	private static String getString(JsonObject object, String key) {
		if (object.has(key)) {
			JsonElement e = object.get(key);
			if (e.isJsonPrimitive() && ((JsonPrimitive) e).isString()) {
				return e.getAsString();
			}
		}
		return "";
	}

	static class PluralKitApi {

		@Getter
		private static final PluralKitApi instance = new PluralKitApi();

		private PluralKitApi() {
		}

		private final HttpClient client = NetworkUtil.createHttpClient("PluralKit Integration; contact: moehreag<at>gmail.com");
		private int remaining = 1;
		private long resetsInMillis = 0;
		private int limit = 2;


		public CompletableFuture<JsonObject> request(HttpUriRequest request) {
			CompletableFuture<JsonObject> cF = new CompletableFuture<>();
			ThreadExecuter.scheduleTask(() -> {
				cF.complete(schedule(request));
			});
			return cF;
		}

		public static CompletableFuture<JsonObject> request(String url) {
			RequestBuilder builder = RequestBuilder.get().setUri(url);
			if (!token.isEmpty()) {
				builder.addHeader("Authorization", token);
			}
			return getInstance().request(builder.build());
		}

		private synchronized JsonObject schedule(HttpUriRequest request) {
			if (remaining == 0) {
				try {
					Thread.sleep(resetsInMillis);
				} catch (InterruptedException ignored) {
				}
				remaining = limit;
			}
			return query(request);
		}

		private JsonObject query(HttpUriRequest request) {
			try {
				HttpResponse response = client.execute(request);

				String responseBody = EntityUtils.toString(response.getEntity());

				remaining = Integer.parseInt(response.getFirstHeader("X-RateLimit-Remaining").getValue());
				resetsInMillis = System.currentTimeMillis() - Long.parseLong(response.getFirstHeader("X-RateLimit-Reset").getValue());
				limit = Integer.parseInt(response.getFirstHeader("X-RateLimit-Reset").getValue());

				return GsonHelper.GSON.fromJson(responseBody, JsonObject.class);
			} catch (IOException e) {
				return new JsonObject();
			}
		}
	}
}
