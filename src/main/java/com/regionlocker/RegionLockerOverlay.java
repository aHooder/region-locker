/*
 * Copyright (c) 2018, Seth <https://github.com/sethtroll>
 * Copyright (c) 2019, Slay to Stay <https://github.com/slaytostay>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.regionlocker;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.RenderOverview;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

class RegionLockerOverlay extends Overlay
{
	private static final Color WHITE_TRANSLUCENT = new Color(255, 255, 255, 127);
	private static final int LABEL_PADDING = 4;
	private static final int REGION_SIZE = 1 << 6;
	// Bitmask to return first coordinate in region
	private static final int REGION_TRUNCATE = ~((1 << 6) - 1);

	private final Client client;
	private final RegionLockerPlugin regionLockerPlugin;
	private final RegionLockerConfig config;

	@Inject
	private RegionLockerOverlay(Client client, RegionLockerPlugin regionLockerPlugin, RegionLockerConfig config)
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(OverlayPriority.HIGHEST);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		this.client = client;
		this.regionLockerPlugin = regionLockerPlugin;
		this.config = config;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config.drawMapOverlay()) drawRegionOverlay(graphics);

		return null;
	}

	private void drawRegionOverlay(Graphics2D graphics)
	{
		Widget map = client.getWidget(WidgetInfo.WORLD_MAP_VIEW);

		if (map == null) return;

		RenderOverview ro = client.getRenderOverview();
		Float pixelsPerTile = ro.getWorldMapZoom();
		Rectangle worldMapRect = map.getBounds();
		graphics.setClip(worldMapRect);

		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

		Point worldMapPosition = ro.getWorldMapPosition();

		// Offset in tiles from anchor sides
		int yTileMin = worldMapPosition.getY() - heightInTiles / 2;
		int xRegionMin = (worldMapPosition.getX() - widthInTiles / 2) & REGION_TRUNCATE;
		int xRegionMax = ((worldMapPosition.getX() + widthInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int yRegionMin = (yTileMin & REGION_TRUNCATE);
		int yRegionMax = ((worldMapPosition.getY() + heightInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int regionPixelSize = (int) Math.ceil(REGION_SIZE * pixelsPerTile);

		Point mousePos = client.getMouseCanvasPosition();

		regionLockerPlugin.setHoveredRegion(-1);
		graphics.setColor(WHITE_TRANSLUCENT);
		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int yTileOffset = -(yTileMin - y);
				int xTileOffset = x + widthInTiles / 2 - worldMapPosition.getX();

				int xPos = ((int) (xTileOffset * pixelsPerTile)) + (int) worldMapRect.getX();
				int yPos = (worldMapRect.height - (int) (yTileOffset * pixelsPerTile)) + (int) worldMapRect.getY();
				// Offset y-position by a single region to correct for drawRect starting from the top
				yPos -= regionPixelSize;

				int regionId = ((x >> 6) << 8) | (y >> 6);
				String regionText = String.valueOf(regionId);
				FontMetrics fm = graphics.getFontMetrics();
				Rectangle2D textBounds = fm.getStringBounds(regionText, graphics);
				Rectangle regionRect = new Rectangle(xPos, yPos, regionPixelSize, regionPixelSize);

				RegionTypes regionType = RegionLocker.getType(regionId);
				boolean containsRegion = (regionType != null) ^ config.invertMapOverlay();
				boolean unlockable = regionType == RegionTypes.UNLOCKABLE;
				boolean blacklisted = regionType == RegionTypes.BLACKLISTED;
				if (containsRegion || unlockable || blacklisted)
				{
					Color color;
					if (blacklisted)
					{
						color = config.blacklistedOverlayColor();
					}
					else if (unlockable)
					{
						color = config.unlockableOverlayColor();
					}
					else
					{
						color = config.mapOverlayColor();
					}
					if (regionRect.contains(mousePos.getX(), mousePos.getY()))
						color = color.brighter();
					graphics.setColor(color);
					graphics.fillRect(xPos, yPos, regionPixelSize, regionPixelSize);
				}


				if (regionRect.contains(mousePos.getX(), mousePos.getY()))
					regionLockerPlugin.setHoveredRegion(regionId);

				graphics.setColor(new Color(0, 19, 36, 127));
				if (config.drawMapGrid()) graphics.drawRect(xPos, yPos, regionPixelSize, regionPixelSize);

				graphics.setColor(WHITE_TRANSLUCENT);
				if (config.drawRegionId())
					graphics.drawString(regionText, xPos + LABEL_PADDING, yPos + (int) textBounds.getHeight() + LABEL_PADDING);
			}
		}

		int currentId = client.getLocalPlayer().getWorldLocation().getRegionID();
		String regionText = String.valueOf(currentId);
		FontMetrics fm = graphics.getFontMetrics();
		Rectangle2D textBounds = fm.getStringBounds(regionText, graphics);
		if (config.drawRegionId()) {
			if (regionLockerPlugin.getHoveredRegion() >= 0)
				graphics.drawString("Hovered chunk: " + regionLockerPlugin.getHoveredRegion(), (int) worldMapRect.getX() + LABEL_PADDING, (int) (worldMapRect.getY() + worldMapRect.getHeight()) - LABEL_PADDING - (int) textBounds.getHeight());
			graphics.drawString("Player chunk: " + regionText, (int) worldMapRect.getX() + LABEL_PADDING, (int) (worldMapRect.getY() + worldMapRect.getHeight()) - LABEL_PADDING);
		}

	}

}
