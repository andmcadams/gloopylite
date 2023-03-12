package com.andmcadams.gloopylite;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Provides;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IconID;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
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
import okhttp3.OkHttpClient;

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
	private ChatMessageManager chatMessageManager;

	@Inject
	private ChatColorConfig chatColorConfig;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private GloopyLiteConfig config;

	final String QUERY_STRING_TAG = "query";
	final Pattern GLOOPY_PATTERN = Pattern.compile("\\[\\[([^#<>\\[\\]{|}]*?)]]");
	final Pattern LINK_PATTERN = Pattern.compile("<" + QUERY_STRING_TAG + "=([^#<>\\[\\]{|}]*?)>");
	final String GLOOPY_MESSAGE_PATTERN = "(<" + QUERY_STRING_TAG + "=.[^#<>\\[\\]{|}]*?>)<col=[a-f0-9]+?><u=[a-f0-9]+?>";
	boolean hideMenuEntries = false;

	final ImmutableMap<ChatMessageType, ChatMessageType> CHAT_MAP = ImmutableMap.<ChatMessageType, ChatMessageType>builder()
		.put(ChatMessageType.FRIENDSCHAT, ChatMessageType.FRIENDSCHATNOTIFICATION)
		.put(ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_MESSAGE)
		.put(ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GUEST_MESSAGE)
		.put(ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.CLAN_GIM_MESSAGE)
		.build();

	@Override
	protected void startUp() throws Exception
	{
		log.info("GloopyLite started!");
		addListenersToChatbox();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("GloopyLite stopped!");
		removeListenersFromChatbox();
	}

	private void sendLinkMessage(String chatMessage, String link, ChatMessageType t)
	{
		final String color = ColorUtil.colorToHexCode(config.textColor());

		// Escape any chars in the message
		final String message = new ChatMessageBuilder()
			.append(chatMessage)
			.build();
		// Need to do this outside ChatMessageBuilder since it will eat my angle brackets
		final StringBuilder taggedMessage = new StringBuilder();
		taggedMessage.append(IconID.CHAIN_LINK)
			.append("<").append(QUERY_STRING_TAG).append("=").append(link).append(">")
			.append("<col=").append(color).append(">")
			.append("<u=").append(color).append(">")
			.append(message)
			.append("</u></col>");

		chatMessageManager.queue(
			QueuedMessage.builder()
				.type(t)
				.runeLiteFormattedMessage(taggedMessage.toString())
				.build());
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick)
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.isMenuOpen())
		{
			return;
		}
		if (hideMenuEntries)
		{
			hideMenuEntries = false;
			client.setMenuEntries(new MenuEntry[0]);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		// Figure out what type of message we need to respond with
		ChatMessageType t = CHAT_MAP.get(chatMessage.getType());

		// Default to a CONSOLE message. This is not ideal since it means it won't appear in public/private only tabs
		if (t == null)
			t = ChatMessageType.CONSOLE;

		String message = chatMessage.getMessage();
		Matcher m = GLOOPY_PATTERN.matcher(message);

		// For each match, add a message about the link.
		while (m.find())
		{
			String searchString = m.group(1).trim();
			if (searchString.isEmpty())
				return;
			log.debug("Creating a link for the search string: " + searchString);
			sendLinkMessage("Search the wiki for \"" + searchString + "\"", searchString, t);
		}
	}

	private void toggleHighlight(Widget w, boolean shouldHighlight)
	{
		String text = w.getText();
		String colorHex = ColorUtil.colorToHexCode(shouldHighlight ? config.highlightTextColor() : config.textColor());

		Matcher m = LINK_PATTERN.matcher(text);
		if (m.find())
		{
			String newText = w.getText().replaceFirst(GLOOPY_MESSAGE_PATTERN,
				"$1<col=" + colorHex + "><u=" + colorHex + ">");
			w.setText(newText);
		}
	}

	private void onClick(Widget w)
	{
		// If the setting for control click is on, require it
		if (!config.requireControlClick() || client.isKeyPressed(KeyCode.KC_CONTROL))
		{
			String text = w.getText();
			Matcher m = LINK_PATTERN.matcher(text);
			if (m.find())
			{
				openLinkInBrowser(m.group(1));
			}
		}
	}

	private void openLinkInBrowser(String searchString)
	{
		// I think this is actually better than using ::wiki since it adds another msg to the chat on click.
		// Also not sure about cs2 stability.
		try
		{
			String wikiUrlEncodedQuery = URLEncoder.encode(searchString.trim(), StandardCharsets.UTF_8.toString());
			String url = new StringBuilder()
				.append("https://oldschool.runescape.wiki/?search=")
				.append(wikiUrlEncodedQuery)
				.append("&title=Special%3ASearch")
				.toString();

			LinkBrowser.browse(url);

			// Go to link in the browser (or maybe fire cs2)
			log.debug("Going to " + url + "...");
		}
		catch (UnsupportedEncodingException e)
		{
			log.error("Error encoding string", e);
		}
	}

	private void maybeHideMenuEntries(Widget w)
	{
		// If we are on a link message (and clicking would open the link), then hide menu entries to avoid walking etc
		if (!config.requireControlClick() || client.isKeyPressed(KeyCode.KC_CONTROL))
		{
			Matcher m = LINK_PATTERN.matcher(w.getText());
			if (m.find())
				hideMenuEntries = !config.requireControlClick() || client.isKeyPressed(KeyCode.KC_CONTROL);
		}
	}

	private void addListenersToChatWidget(Widget w)
	{
		w.setOnMouseRepeatListener((JavaScriptCallback) ev -> maybeHideMenuEntries(w));
		w.setOnMouseOverListener((JavaScriptCallback) ev -> toggleHighlight(w, true));
		w.setOnMouseLeaveListener((JavaScriptCallback) ev -> toggleHighlight(w, false));
		w.setOnClickListener((JavaScriptCallback) ev -> onClick(w));
		w.setHasListener(true);
	}

	private void addListenersToChatbox()
	{
		Widget chatbox = client.getWidget(WidgetInfo.CHATBOX_MESSAGE_LINES);
		// This doesn't seem to overwrite any vanilla listeners but this would definitely conflict with someone
		// doing the same thing.
		// This is incredibly stupid, but I am not sure of a better way to do this.
		// There doesn't seem to be an onWidgetChanged event, although maybe I could listen for any of these to change
		if (chatbox == null || chatbox.getDynamicChildren() == null)
			return;
		for (int i = 0; i < chatbox.getDynamicChildren().length; i += 4)
		{
			// Sometimes we want this widget (CONSOLE messages for ex)
			Widget w = chatbox.getDynamicChildren()[i];
			addListenersToChatWidget(w);

			// Unless we actually want this widget (clan messages for ex)
			Widget w2 = chatbox.getDynamicChildren()[i+1];
			addListenersToChatWidget(w2);
		}
	}

	private void removeListenersFromChatWidget(Widget w)
	{
		w.setOnMouseOverListener((Object[]) null);
		w.setOnMouseLeaveListener((Object[]) null);
		w.setOnClickListener((Object[]) null);
		w.setHasListener(false);
	}

	private void removeListenersFromChatbox()
	{
		Widget chatbox = client.getWidget(WidgetInfo.CHATBOX_MESSAGE_LINES);
		// Is this going to overwrite some listeners?
		// This is incredibly stupid, but I am not sure of a better way to do this.
		// There doesn't seem to be an onWidgetChanged event, although maybe I could listen for any of these to change
		if (chatbox == null || chatbox.getDynamicChildren() == null)
			return;
		for (int i = 0; i < chatbox.getDynamicChildren().length; i += 4)
		{
			Widget w = chatbox.getDynamicChildren()[i];
			removeListenersFromChatWidget(w);

			Widget w2 = chatbox.getDynamicChildren()[i+1];
			removeListenersFromChatWidget(w2);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widgetLoaded)
	{
		int id = widgetLoaded.getGroupId();
		if (id == WidgetID.CHATBOX_GROUP_ID)
			addListenersToChatbox();
	}

	@Provides
	GloopyLiteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GloopyLiteConfig.class);
	}
}
