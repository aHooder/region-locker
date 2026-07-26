package com.gpu;

import com.google.common.io.CharStreams;
import com.regionlocker.RegionLocker;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.gpu.api.GpuExtension;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
@Singleton
public class RegionLockerGpuExtension extends GpuExtension
{
	@Inject
	private Client client;

	private static final int LOCKED_REGIONS_SIZE = 16;
	private final int[] loadedLockedRegions = new int[LOCKED_REGIONS_SIZE];

	private int uniUseGray;
	private int uniUseHardBorder;
	private int uniGrayAmount;
	private int uniGrayColor;
	private int uniBaseX;
	private int uniBaseY;
	private int uniLockedRegions;

	private int glProgram;

	@Override
	public void onContextCreate()
	{

	}

	@Override
	public void onContextDestroy()
	{
		glProgram = 0;
	}

	@Override
	public String getShaderExtension(String hook)
	{
		switch (hook)
		{
			case "rlst_vert_definitions":
				return loadString("vert.glsl");
			case "rlst_vert_main_post":
				return "region_locker_vert(worldPos.xyz);";
			case "rlst_frag_definitions":
				return loadString("frag.glsl");
			case "rlst_frag_main_post":
				return "region_locker_frag(FragColor);";
			default:
				return null;
		}
	}

	public String loadString(String file)
	{
		try (InputStream is = RegionLockerGpuExtension.class.getResourceAsStream(file))
		{
			if (is != null)
			{
				return CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
			}
		}
		catch (IOException ex)
		{
			log.warn(null, ex);
		}
		return null;
	}

	@Override
	public void onPostDrawToplevel()
	{
		try
		{
			if (client.getGameState().getState() < GameState.LOADING.getState())
			{
				return;
			}

			var vw = client.getTopLevelWorldView();
			if (vw == null)
			{
				return;
			}

			int glProgram = glGetInteger(GL_CURRENT_PROGRAM);
			if (glProgram != this.glProgram)
			{
				this.glProgram = glProgram;

				uniUseGray = glGetUniformLocation(glProgram, "region_locker_useGray");
				uniUseHardBorder = glGetUniformLocation(glProgram, "region_locker_useHardBorder");
				uniGrayAmount = glGetUniformLocation(glProgram, "region_locker_configGrayAmount");
				uniGrayColor = glGetUniformLocation(glProgram, "region_locker_configGrayColor");
				uniBaseX = glGetUniformLocation(glProgram, "region_locker_baseX");
				uniBaseY = glGetUniformLocation(glProgram, "region_locker_baseY");
				uniLockedRegions = glGetUniformLocation(glProgram, "region_locker_lockedRegions");

				checkGLErrors();
			}

			glUniform1i(uniUseHardBorder, RegionLocker.hardBorder ? 1 : 0);
			glUniform1f(uniGrayAmount, RegionLocker.grayAmount / 255f);
			glUniform4f(uniGrayColor,
				RegionLocker.grayColor.getRed() / 255f,
				RegionLocker.grayColor.getGreen() / 255f,
				RegionLocker.grayColor.getBlue() / 255f,
				RegionLocker.grayColor.getAlpha() / 255f
			);

			var mapRegions = vw.getMapRegions();

			boolean isUnlockedInstance = false;
			if (vw.isInstance())
			{
				if (mapRegions != null)
				{
					for (int region : mapRegions)
					{
						if (RegionLocker.hasRegion(region))
						{
							isUnlockedInstance = true;
							break;
						}
					}
				}
			}

			if (!RegionLocker.renderLockedRegions || isUnlockedInstance)
			{
				glUniform1i(uniUseGray, 0);
			}
			else
			{
				glUniform1i(uniUseGray, 1);
				glUniform1i(uniBaseX, vw.getBaseX() * 128);
				glUniform1i(uniBaseY, vw.getBaseY() * 128);

				Arrays.fill(loadedLockedRegions, 0);
				if (mapRegions != null)
				{
					for (int i = 0; i < mapRegions.length; i++)
					{
						int region = mapRegions[i];
						if (RegionLocker.invertShader != RegionLocker.hasRegion(region))
						{
							loadedLockedRegions[i] = region;
						}
					}
				}

				glUniform1iv(uniLockedRegions, loadedLockedRegions);
			}

			checkGLErrors();
		}
		catch (Throwable ex)
		{
			log.error("Error updating region locker uniforms", ex);
		}
	}

	private void checkGLErrors()
	{
		int error;
		while ((error = glGetError()) != GL_NO_ERROR)
		{
			log.error("glGetError: {}", error);
		}
	}
}
