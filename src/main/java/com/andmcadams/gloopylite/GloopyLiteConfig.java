package com.andmcadams.gloopylite;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gloopylite")
public interface GloopyLiteConfig extends Config
{
	@ConfigItem(
		keyName = "controlClick",
		name = "Require control click",
		description = "Only open links if you are holding control while clicking"
	)
	default boolean requireControlClick()
	{
		return true;
	}

	@ConfigItem(
		keyName = "textColor",
		name = "Text color",
		description = "The color of the link message"
	)
	default Color textColor()
	{
		return new Color(0x0000FF);
	}

	@ConfigItem(
		keyName = "highlightTextColor",
		name = "Text color (highlight)",
		description = "The color of the link message when hovered"
	)
	default Color highlightTextColor()
	{
		return new Color(0x3D3DFF);
	}
}
