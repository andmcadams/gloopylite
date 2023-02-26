package com.andmcadams.gloopylite;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GloopyLitePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GloopyLitePlugin.class);
		RuneLite.main(args);
	}
}