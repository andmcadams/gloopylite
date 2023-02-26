package com.andmcadams.gloopylite;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.inject.Provides;
import java.awt.Color;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ChatColorConfig;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "GloopyLite"
)
public class GloopyLitePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GloopyLiteConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ChatColorConfig chatColorConfig;

	@Inject
	private OkHttpClient okHttpClient;

	@Override
	protected void startUp() throws Exception
	{
		log.info("GloopyLite started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("GloopyLite stopped!");
	}

	final Pattern GLOOPY_PATTERN = Pattern.compile("\\[\\[(.*?)]]");

	final int TEXT_COLOR = 0x0000FF;
	final int TEXT_HIGHLIGHT_COLOR = 0xFF00FF;
	final String hex = ColorUtil.colorToHexCode(new Color(TEXT_COLOR));
	final String hexHighlight = ColorUtil.colorToHexCode(new Color(TEXT_HIGHLIGHT_COLOR));

	private void sendChatMessage(String chatMessage, String link, ChatMessageType t)
	{
		final String message = new ChatMessageBuilder()
			.append(chatMessage)
			.build();
		final String underlinedMessage = new StringBuilder()
			.append("<link=")
			.append(link)
			.append(">")
			.append("<col=")
			.append(hex)
			.append(">")
			.append("<u=")
			.append(hex)
			.append(">")
			.append(message)
			.append("</u></col>").toString();

		chatMessageManager.queue(
			QueuedMessage.builder()
				.type(t)
				.runeLiteFormattedMessage(underlinedMessage)
				.build());
	}

	final Pattern p = Pattern.compile("https://oldschool\\.runescape\\.wiki/(w/.*)");
	private void attemptToAddLink(String query, ChatMessageType t)
	{
		// Wiki url encode
		String wikiUrlEncodedQuery = URLEncoder.encode(query.trim());
		log.error("Encoded query: " + wikiUrlEncodedQuery);
		String url = new StringBuilder()
			.append("https://oldschool.runescape.wiki/api.php?action=opensearch&search=")
			.append(wikiUrlEncodedQuery)
			.append("&redirects=resolve")
			.toString();
		// Make the request
		Request r = new Request.Builder()
			.url(url)
			.get().build();
		okHttpClient.newCall(r).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Oh no, a wittle fucky wucky happened :(((((");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				JsonArray queryResponse = new Gson().fromJson(response.body().string(), JsonArray.class);
				JsonArray pageNames = queryResponse.get(1).getAsJsonArray();
				if (pageNames.size() == 0)
					return;
				if ("Nonexistence".equals(pageNames.get(0).getAsString()))
				{
					log.warn("Not linking, page is not existent");
					return;
				}

				String link = queryResponse.get(3).getAsJsonArray().get(0).getAsString();
				Matcher m = p.matcher(link);
				if (m.find())
				{
					sendChatMessage("Wiki link to " + pageNames.get(0), m.group(1), t);
					log.error("Found page " + pageNames.get(0));
				}
			}
		});
	}

	final ImmutableMap<ChatMessageType, ChatMessageType> CHAT_MAP = ImmutableMap.<ChatMessageType, ChatMessageType>builder()
		// Public is wrong here but I don't know if there is a better option
		.put(ChatMessageType.PUBLICCHAT, ChatMessageType.CONSOLE)
		.put(ChatMessageType.FRIENDSCHAT, ChatMessageType.FRIENDSCHATNOTIFICATION)
		.put(ChatMessageType.PRIVATECHAT, ChatMessageType.PRIVATECHAT)
		.put(ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_MESSAGE)
		.put(ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GUEST_MESSAGE)
		.put(ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.CLAN_GIM_MESSAGE)
		.build();

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		// If the message is from the cc
		ChatMessageType t = CHAT_MAP.get(chatMessage.getType());

		// If we don't find any matches, escape now!
		if (t == null)
			return;

		log.warn("Message received: " + chatMessage.getMessage());
		String message = chatMessage.getMessage();
		Matcher m = GLOOPY_PATTERN.matcher(message);
		while (m.find())
		{
			String page = m.group(1);
			log.warn("Attempting to create a link for the page: " + page);
			attemptToAddLink(page, t);
		}
	}

	final Pattern LINK_PATTERN = Pattern.compile("<link=(.*?)>");
	private void unhighlightLink(Widget w)
	{
		String text = w.getText();

		Matcher m = LINK_PATTERN.matcher(text);
		if (m.find())
		{
			String newText = w.getText().replaceFirst("<col=[a-f0-9]+?><u=[a-f0-9]+?>",
				"<col=" + hex + "><u=" + hex + ">");
			log.error("Unhighlighted text: " + w.getText() + " -> " + newText);
			w.setText(newText);
		}
	}

	private void highlightLink(Widget w)
	{
		String text = w.getText();

		Matcher m = LINK_PATTERN.matcher(text);
		if (m.find())
		{
			String newText = w.getText().replaceFirst("<col=[a-f0-9]+?><u=[a-f0-9]+?>",
				"<col=" + hexHighlight + "><u=" + hexHighlight + ">");
			log.error("Highlighted text: " + w.getText() + " -> " + newText);
			w.setText(newText);
		}
	}

	private void onKeyPress(Widget w, int keyCode)
	{
		if (keyCode != 0)
			return;
		String text = w.getText();
		// Parse link from text via tag

		// This could potentially be dangerous if someone else makes a plugin that matches this regex, then clicking
		// a message could take you to a bad website.
		// Make sure to only go if the url matches the wiki
		Matcher m = LINK_PATTERN.matcher(text);
		if (m.find())
		{
			String link = "https://oldschool.runescape.wiki/" + m.group(1);

			LinkBrowser.browse(link);

			// Go to link in the browser (or maybe fire cs2)
			log.error("Going to " + link + "...");
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		int id = widgetLoaded.getGroupId();
		if (id == WidgetID.CHATBOX_GROUP_ID)
		{
			Widget chatbox = client.getWidget(WidgetInfo.CHATBOX_MESSAGE_LINES);
			// Is this going to overwrite some listeners?
			for (int i = 0; i < 2000; i += 4)
			{
				Widget w = chatbox.getDynamicChildren()[i];
				w.setOnMouseOverListener((JavaScriptCallback) ev -> highlightLink(w));
				w.setOnMouseLeaveListener((JavaScriptCallback) ev -> unhighlightLink(w));
				w.setOnClickListener((JavaScriptCallback) ev -> onKeyPress(w, ev.getTypedKeyCode()));
				w.setHasListener(true);

				Widget w2 = chatbox.getDynamicChildren()[i+1];
				w2.setOnMouseOverListener((JavaScriptCallback) ev -> highlightLink(w2));
				w2.setOnMouseLeaveListener((JavaScriptCallback) ev -> unhighlightLink(w2));
				w2.setOnClickListener((JavaScriptCallback) ev -> onKeyPress(w2, ev.getTypedKeyCode()));
				w2.setHasListener(true);
			}

		}
	}

	@Provides
	GloopyLiteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GloopyLiteConfig.class);
	}
}
