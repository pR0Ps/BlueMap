package de.bluecolored.bluemap.core.config;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;

import com.google.common.base.Preconditions;

import de.bluecolored.bluemap.core.render.RenderSettings;
import de.bluecolored.bluemap.core.web.WebServerConfig;
import ninja.leaping.configurate.ConfigurationNode;

public class Configuration implements WebServerConfig {

private String version;
	
	private boolean downloadAccepted = false;
	
	private boolean webserverEnabled = true;
	private int webserverPort = 8100;
	private int webserverMaxConnections = 100;
	private InetAddress webserverBindAdress = null;

	private Path dataPath = Paths.get("data");
	
	private Path webRoot = Paths.get("web");
	private Path webDataPath = webRoot.resolve("data");
	
	private int renderThreadCount = 0;
	
	private Collection<MapConfig> mapConfigs = new ArrayList<>();
	
	public Configuration(ConfigurationNode node) throws IOException {
		version = node.getNode("version").getString("-");
		downloadAccepted = node.getNode("accept-download").getBoolean(false);
		
		dataPath = toFolder(node.getNode("data").getString("data"));
		
		loadWebConfig(node.getNode("web"));

		int defaultCount = Runtime.getRuntime().availableProcessors();
		renderThreadCount = node.getNode("renderThreadCount").getInt(defaultCount);
		if (renderThreadCount <= 0) renderThreadCount = defaultCount;
		
		loadMapConfigs(node.getNode("maps"));
	}
	
	private void loadWebConfig(ConfigurationNode node) throws IOException {
		webserverEnabled = node.getNode("enabled").getBoolean(false);
		
		String webRootString = node.getNode("webroot").getString();
		if (webserverEnabled && webRootString == null) throw new IOException("Invalid configuration: Node web.webroot is not defined");
		webRoot = toFolder(webRootString);

		if (webserverEnabled) {
			webserverPort = node.getNode("port").getInt(8100);
			webserverMaxConnections = node.getNode("maxConnectionCount").getInt(100);
			
			String webserverBindAdressString = node.getNode("ip").getString("");
			if (webserverBindAdressString.isEmpty()) {
				webserverBindAdress = InetAddress.getLocalHost();
			} else {
				webserverBindAdress = InetAddress.getByName(webserverBindAdressString);
			}
		}
		
		String webDataString = node.getNode("web-data").getString(node.getNode("data").getString());
		if (webDataString != null) 
			webDataPath = toFolder(webDataString);
		else if (webRoot != null)
			webDataPath = webRoot.resolve("data");
		else
			throw new IOException("Invalid configuration: Node web.data is not defined in config");
	}
	
	private void loadMapConfigs(ConfigurationNode node) throws IOException {
		mapConfigs = new ArrayList<>();
		for (ConfigurationNode mapConfigNode : node.getChildrenList()) {
			mapConfigs.add(new MapConfig(mapConfigNode));
		}
	}
	
	private Path toFolder(String pathString) throws IOException {
		Preconditions.checkNotNull(pathString);
		
		File file = new File(pathString);
		if (file.exists() && !file.isDirectory()) throw new IOException("Invalid configuration: Path '" + file.getAbsolutePath() + "' is a file (should be a directory)");
		if (!file.exists() && !file.mkdirs()) throw new IOException("Invalid configuration: Folders to path '" + file.getAbsolutePath() + "' could not be created");
		return file.toPath();
	}

	public Path getDataPath() {
		return dataPath;
	}
	
	public boolean isWebserverEnabled() {
		return webserverEnabled;
	}
	
	public Path getWebDataPath() {
		return webDataPath;
	}

	@Override
	public int getWebserverPort() {
		return webserverPort;
	}

	@Override
	public int getWebserverMaxConnections() {
		return webserverMaxConnections;
	}

	@Override
	public InetAddress getWebserverBindAdress() {
		return webserverBindAdress;
	}

	@Override
	public Path getWebRoot() {
		return webRoot;
	}
	
	public String getVersion() {
		return version;
	}
	
	public boolean isDownloadAccepted() {
		return downloadAccepted;
	}
	
	public int getRenderThreadCount() {
		return renderThreadCount;
	}
	
	public Collection<MapConfig> getMapConfigs(){
		return mapConfigs;
	}
	
	public class MapConfig implements RenderSettings {
		
		private String id;
		private String name;
		private String world;
		
		private boolean renderCaves;
		private float ambientOcclusion;
		private float lighting;
		
		private int maxY, minY, sliceY;
		
		private int hiresTileSize;
		private float hiresViewDistance;
		
		private int lowresPointsPerHiresTile;
		private int lowresPointsPerLowresTile;
		private float lowresViewDistance;
		
		private MapConfig(ConfigurationNode node) throws IOException {
			this.id = node.getNode("id").getString("");
			if (id.isEmpty()) throw new IOException("Invalid configuration: Node maps[?].id is not defined");
			
			this.name = node.getNode("name").getString(id);
			
			this.world = node.getNode("world").getString("");
			if (world.isEmpty()) throw new IOException("Invalid configuration: Node maps[?].world is not defined");
			
			this.renderCaves = node.getNode("renderCaves").getBoolean(false);
			this.ambientOcclusion = node.getNode("ambientOcclusion").getFloat(0.25f);
			this.lighting = node.getNode("lighting").getFloat(0.8f);
			
			this.maxY = node.getNode("maxY").getInt(RenderSettings.super.getMaxY());
			this.minY = node.getNode("minY").getInt(RenderSettings.super.getMinY());
			this.sliceY = node.getNode("sliceY").getInt(RenderSettings.super.getSliceY());
			
			this.hiresTileSize = node.getNode("hires", "tileSize").getInt(32);
			this.hiresViewDistance = node.getNode("hires", "viewDistance").getFloat(3.5f);
			
			this.lowresPointsPerHiresTile = node.getNode("lowres", "pointsPerHiresTile").getInt(4);
			this.lowresPointsPerLowresTile = node.getNode("lowres", "pointsPerLowresTile").getInt(50);
			this.lowresViewDistance = node.getNode("lowres", "viewDistance").getFloat(4f);
			
			//check valid configuration values
			double blocksPerPoint = (double) this.hiresTileSize / (double) this.lowresPointsPerHiresTile;
			if (blocksPerPoint != Math.floor(blocksPerPoint)) throw new IOException("Invalid configuration: Invalid map resolution settings of map " + id + ": hires.tileSize / lowres.pointsPerTile has to be an integer result");
		}
		
		public String getId() {
			return id;
		}
		
		public String getName() {
			return name;
		}
		
		public String getWorldPath() {
			return world;
		}

		public boolean isRenderCaves() {
			return renderCaves;
		}

		@Override
		public float getAmbientOcclusionStrenght() {
			return ambientOcclusion;
		}

		@Override
		public float getLightShadeMultiplier() {
			return lighting;
		}
		
		public int getHiresTileSize() {
			return hiresTileSize;
		}

		public float getHiresViewDistance() {
			return hiresViewDistance;
		}

		public int getLowresPointsPerHiresTile() {
			return lowresPointsPerHiresTile;
		}

		public int getLowresPointsPerLowresTile() {
			return lowresPointsPerLowresTile;
		}

		public float getLowresViewDistance() {
			return lowresViewDistance;
		}

		@Override
		public boolean isExcludeFacesWithoutSunlight() {
			return !isRenderCaves();
		}
		
		@Override
		public int getMaxY() {
			return maxY;
		}
		
		@Override
		public int getMinY() {
			return minY;
		}
		
		@Override
		public int getSliceY() {
			return sliceY;
		}
		
	}
	
}