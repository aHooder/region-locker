package com.gpu;

import com.regionlocker.RegionLocker;
import java.util.Arrays;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.Shader;
import net.runelite.client.plugins.gpu.template.Template;

import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform1iv;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

@Slf4j
class ModifiedGpuPlugin extends GpuPlugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	private final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "REGION_LOCKER_VERT")
		.add(GL_FRAGMENT_SHADER, "REGION_LOCKER_FRAG");

	private static final int LOCKED_REGIONS_SIZE = 16;
	private final int[] loadedLockedRegions = new int[LOCKED_REGIONS_SIZE];

	private int dummyVao;

	private int uniUseGray;
	private int uniUseHardBorder;
	private int uniGrayAmount;
	private int uniGrayColor;
	private int uniBaseX;
	private int uniBaseY;
	private int uniLockedRegions;

	void start()
	{
		super.startUp();
	}

	void stop()
	{
		clientThread.invoke(() -> {
			if (dummyVao != 0)
			{
				glDeleteVertexArrays(dummyVao);
				dummyVao = 0;
			}

			super.shutDown();
		});
	}

	@Override
	public void initProgram()
	{
		// Compile whichever shaders the GPU plugin wants, then replace the scene shader with a modified one
		super.initProgram();

		Template template = createTemplate()
			.addInclude(RegionLockerGpuPlugin.class);
		template
			.add(key -> {
				String source, addon;
				switch (key)
				{
					case "REGION_LOCKER_VERT":
					{
						source = template.load("vert.glsl");
						addon = template.load("regionlocker_vert.glsl");
						break;
					}
					case "REGION_LOCKER_FRAG":
					{
						source = template.load("frag.glsl");
						addon = template.load("regionlocker_frag.glsl");
						break;
					}
					default:
						return null;
				}

				final String mainDeclaration = "void main() {";
				int mainIndex = source.indexOf(mainDeclaration);
				if (mainIndex == -1)
				{
					throw new RuntimeException("Unable to locate main function");
				}
				String prefix = source.substring(0, mainIndex);
				String suffix = source.substring(mainIndex);

				switch (key)
				{
					case "REGION_LOCKER_VERT":
					{
						// Add a line to call region locker's vertex shader function
						final String worldPosLine = "vec4 worldPos = entityProj * vert;";
						int worldPosIndex = suffix.indexOf(worldPosLine, mainDeclaration.length());
						if (worldPosIndex == -1)
						{
							throw new RuntimeException("Unable to locate vec4 worldPos");
						}
						worldPosIndex += worldPosLine.length();
						suffix = suffix.substring(0, worldPosIndex) +
							"\nregion_locker_vert(worldPos.xyz);\n" +
							suffix.substring(worldPosIndex);
						break;
					}
					case "REGION_LOCKER_FRAG":
					{
						// Add a line to call region locker's vertex shader function
						final String outputLine = "FragColor = vec4(mixedColor, c.a);";
						int outputIndex = suffix.indexOf(outputLine, mainDeclaration.length());
						if (outputIndex == -1)
						{
							throw new RuntimeException("Unable to locate FragColor write");
						}
						outputIndex += outputLine.length();
						suffix = suffix.substring(0, outputIndex) +
							"\nregion_locker_frag(FragColor);\n" +
							suffix.substring(outputIndex);
						break;
					}

				}

				// Create and compile the new shader
				String newSource = prefix + addon + "\n" + suffix;
				log.debug("Replacing shader source with:\n{}", newSource);
				return newSource;
			});

		// Replace the GPU plugin's scene program
		if (dummyVao == 0)
		{
			dummyVao = glGenVertexArrays();
		}
		glBindVertexArray(dummyVao);
		glProgram = PROGRAM.compile(template);
		glBindVertexArray(0);

		initUniforms();

		uniUseGray = glGetUniformLocation(glProgram, "region_locker_useGray");
		uniUseHardBorder = glGetUniformLocation(glProgram, "region_locker_useHardBorder");
		uniGrayAmount = glGetUniformLocation(glProgram, "region_locker_configGrayAmount");
		uniGrayColor = glGetUniformLocation(glProgram, "region_locker_configGrayColor");
		uniBaseX = glGetUniformLocation(glProgram, "region_locker_baseX");
		uniBaseY = glGetUniformLocation(glProgram, "region_locker_baseY");
		uniLockedRegions = glGetUniformLocation(glProgram, "region_locker_lockedRegions");

		checkGLErrors();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
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

			// Get the currently bound program, so we can restore the state later if needed
			int currentProgram = glGetInteger(GL_CURRENT_PROGRAM);
			if (currentProgram != glProgram)
			{
				glUseProgram(glProgram);
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

			// Restore the state
			if (glProgram != currentProgram)
			{
				glUseProgram(currentProgram);
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
