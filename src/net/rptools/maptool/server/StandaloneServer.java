package net.rptools.maptool.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

import net.rptools.lib.FileUtil;
import net.rptools.lib.MD5Key;
import net.rptools.lib.ModelVersionManager;
import net.rptools.lib.io.PackedFile;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetTransferHandler;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.MapToolRegistry;
import net.rptools.maptool.client.ServerCommandClientImpl;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignFactory;
import net.rptools.maptool.model.transform.campaign.AssetNameTransform;
import net.rptools.maptool.model.transform.campaign.PCVisionTransform;
import net.rptools.maptool.transfer.AssetTransferManager;
import net.rptools.maptool.util.PersistenceUtil.PersistedCampaign;
import net.rptools.maptool.util.StringUtil;

import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class StandaloneServer {
	private static final Logger log = Logger.getLogger(StandaloneServer.class);

	private static final ModelVersionManager campaignVersionManager = new ModelVersionManager();
	private static final ModelVersionManager assetnameVersionManager = new ModelVersionManager();

	private static MapToolServer server;
	private static ServerCommand serverCommand;
	private static ShutDownHandler shutDownHandler;

	private static class ShutDownHandler extends Thread {
		private static final String PROP_VERSION = "version"; //$NON-NLS-1$
		private static final String PROP_CAMPAIGN_VERSION = "campaignVersion"; //$NON-NLS-1$
		private static final String ASSET_DIR = "assets/"; //$NON-NLS-1$
		private static final String CAMPAIGN_VERSION = "1.3.85";
		
		private Campaign campaign;
		private File file;

		public void run() {
			try {
				if ((this.campaign != null) && (this.file != null)) {
					log.info("Saving campaign");
					//This would have been too easy, wouldn't it?
					//PersistenceUtil.saveCampaign(this.campaign, this.file);
					this.saveCampaign();
				}
			} catch (IOException e) {
				log.error("Failed to save campaign!");
			}
		}
		
		private void saveCampaign() throws IOException {			
			File tmpDir = AppUtil.getTmpDir();
			File tmpFile = new File(tmpDir.getAbsolutePath(), this.file.getName());
			if (tmpFile.exists()) {
				tmpFile.delete();
			}
			
			PackedFile pakFile = null;
			try {
				pakFile = new PackedFile(tmpFile);
				// Configure the meta file (this is for legacy support)
				PersistedCampaign persistedCampaign = new PersistedCampaign();
				persistedCampaign.campaign = this.campaign;

				Set<MD5Key> allAssetIds = this.campaign.getAllAssetIds();

				// Special handling of assets:  XML file to describe the Asset, but binary file for the image data
				pakFile.getXStream().processAnnotations(Asset.class);

				// Store the assets
				for (MD5Key assetId : allAssetIds) {
					if (assetId == null)
						continue;

					/*
					 * This is a PITA. The way the Asset class determines the image extension when creating an 'Asset'
					 * object is by opening up the file and passing it through a stream reader. Unfortunately that 
					 * tries to register a shutdown hook which you can't do when processing a shutdown hook. The workaround
					 * is disabling the persistent cache at save time. This should cause us to pull assets directly out
					 * of the assetMap (memory). The down side is that we've created a race condition. If we exit before
					 * all of the assets are loaded into memory, they're not going to be available and we'll "corrupt" the
					 * campaign file.
					 * 
					 * Alternatively, we can wedge a bogus remote repo into the campaign properties object via
					 * Campaign.getCampaignProperties and Campaign.mergeCampaignProperties
					 * and then try to use AssetManager.findAllAssetsNotInRepositories
					 * I'm not sure this would work, though.
					 */
					AssetManager.setUsePersistentCache(false);

					Asset asset = AssetManager.getAsset(assetId);
					if (asset == null) {
						log.error("Asset " + assetId + " not found while saving");
						continue;
					}
					persistedCampaign.assetMap.put(assetId, null);
					pakFile.putFile(ASSET_DIR + assetId + "." + asset.getImageExtension(), asset.getImage());
					pakFile.putFile(ASSET_DIR + assetId, asset); // Does not write the image
					log.debug("Asset " + assetId + " saved correctly.");
				}

				// Write the actual pakfile out
				try {
					pakFile.setContent(persistedCampaign);
					pakFile.setProperty(PROP_VERSION, MapTool.getVersion());
					pakFile.setProperty(PROP_CAMPAIGN_VERSION, CAMPAIGN_VERSION);

					pakFile.save();
				} catch (OutOfMemoryError oom) {
					/*
					 * This error is normally because the heap space has been
					 * exceeded while trying to save the campaign. It's not recoverable the way we're handling
					 * the standalone server right now.
					 */
					pakFile.close(); // Have to close the tmpFile first on some OSes
					pakFile = null;
					tmpFile.delete(); // Delete the temporary file
					log.error("Failed to save campaign due to OOM");
					return;
				}
			} finally {
				try {
					if (pakFile != null)
						pakFile.close();
				} catch (Exception e) {
					log.error("Failed to write cmpgn file");
				}
				pakFile = null;
			}

			// Copy to the new location
			// Not the fastest solution in the world if renameTo() fails, but worth the safety net it provides
			File bakFile = new File(tmpDir.getAbsolutePath(), this.file.getName() + ".bak");
			bakFile.delete();
			if (this.file.exists()) {
				if (!this.file.renameTo(bakFile)) {
					FileUtil.copyFile(this.file, bakFile);
					this.file.delete();
				}
			}
			if (!tmpFile.renameTo(this.file)) {
				FileUtil.copyFile(tmpFile, this.file);
				tmpFile.delete();
			}
			if (bakFile.exists()) {
				bakFile.delete();
			}
		}
		
		public void setCampaign(Campaign campaign) {
			this.campaign = campaign;
		}
		
		public void setFile(File file) {
			this.file = file;
		}
	}

	static {
		PackedFile.init(AppUtil.getAppHome("tmp"));
		campaignVersionManager.registerTransformation("1.3.51", new PCVisionTransform());
		assetnameVersionManager.registerTransformation("1.3.51", new AssetNameTransform("^(.*)\\.(dat)?$", "$1"));
	}

	private static void loadAssets(Collection<MD5Key> assetIds, PackedFile pakFile) throws IOException {
		pakFile.getXStream().processAnnotations(Asset.class);
		String campVersion = (String)pakFile.getProperty("campaignVersion");
		
		for (MD5Key key : assetIds) {
			if (key == null) {
				continue;
			}

			if (!AssetManager.hasAsset(key)) {
				String pathname = "assets/" + key;
				Asset asset = null;
				
				// TODO: This is lame. We should retry here.
				try {
					asset = (Asset) pakFile.getFileObject(pathname);
				} catch (Exception e) {
					log.info("Exception while handling asset '" + pathname + "'", e);
					continue;
				}

				if (asset == null) {
					log.error("Referenced asset '" + pathname + "' not found while loading?!");
					continue;
				}
				if ("broken".equals(asset.getName())) {
					log.warn("Reference to 'broken' asset '" + pathname + "' not restored.");
					continue;
				}
				if (asset.getImage() == null || asset.getImage().length < 4) {
					String ext = asset.getImageExtension();
					pathname = pathname + "." + (StringUtil.isEmpty(ext) ? "dat" : ext);
					pathname = assetnameVersionManager.transform(pathname, campVersion);
					InputStream is = pakFile.getFileAsInputStream(pathname);
					asset.setImage(IOUtils.toByteArray(is));
					is.close();
				}

				AssetManager.putAsset(asset);
				serverCommand.putAsset(asset);
			}
		}
	}

	public static Campaign loadCampaignFile(File campaignFile) throws IOException {
		PackedFile pakfile = new PackedFile(campaignFile);
		pakfile.setModelVersionManager(campaignVersionManager);

		String version = (String)pakfile.getProperty("campaignVersion");
		version = version == null ? "1.3.50" : version;
		PersistedCampaign persistedCampaign = (PersistedCampaign) pakfile.getContent(version);

		if (persistedCampaign != null) {
			server.setCampaign(persistedCampaign.campaign);

			Set<MD5Key> allAssetIds = persistedCampaign.assetMap.keySet();
			loadAssets(allAssetIds, pakfile);
		}
		
		return persistedCampaign.campaign;
	}

	private static void startServer(String id, ServerConfig config, ServerPolicy policy, Campaign campaign) throws IOException {
		AssetTransferManager assetTransferManager = new AssetTransferManager();
		assetTransferManager.addConsumerListener(new AssetTransferHandler());
		assetTransferManager.flush();

		server = new MapToolServer(config, policy);
		server.setCampaign(campaign);

		if (config.isServerRegistered()) {
			log.debug("Attempting To Register Server");
			try {
				int result = MapToolRegistry.registerInstance(config.getServerName(), config.getPort());
				if (result == 3) {
					log.error("Already Registered", null);
					System.exit(1);
				}
			} catch (Exception e) {
				log.error("Could Not Register Server", e);
				System.exit(1);
			}
		} else {
			log.debug("Will Not Register Server");
		}
	}


	public static void main(String [] args) {
		org.apache.log4j.BasicConfigurator.configure();

		Options options = new Options();
		options.addOption("h", "help", false, "Show this help");
		Option name = new Option("n", "name", true, "The name of the maptools server and registers at rptools.net if set");
		name.setValueSeparator('=');
		name.setArgName("SERVERNAME");
		options.addOption(name);
		Option port = new Option("p", "port", true, "The port of the maptools server, defaults to 51234");
		port.setValueSeparator('=');
		port.setArgName("PORT");
		options.addOption(port);
		Option gm = new Option("g", "gmPassword", true, "The GM password a user has to enter, if he wants to be a GM");
		gm.setValueSeparator('=');
		gm.setArgName("PASSWORD");
		options.addOption(gm);
		Option password = new Option("a", "playerPassword", true, "The player password a user has to enter");
		password.setValueSeparator('=');
		password.setArgName("PASSWORD");
		options.addOption(password);
		options.addOption("s", "useStrictTokenManagement", false, "Set the strict token management");
		options.addOption("v", "playersCanRevealVision", false, "The users can reveal the vision");
		options.addOption("e", "autoRevealOnMovement", false, "Vision is auto-revealed on movement (implies -v)");
		options.addOption("i", "useIndividualViews", false, "Use individual views for each player");
		options.addOption("m", "playersReceiveCampaignMacros", false, "Send the campaign macros to the users");
		options.addOption("t", "useToolTipsForDefaultRollFormat", false, "");
		options.addOption("r", "restrictedImpersonation", false, "Restrict the impersonation of a token to only one user");
		options.addOption("c", "campaign", true, "The campaign file to load");
		options.addOption("o", "saveOnExit", false, "Save the campaign file when exiting");
		options.addOption("d", "logDebug", false, "Show debug information");
		CommandLineParser parser = new BasicParser();
		CommandLine cmd = null;

		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			log.error("Something went wrong fetching the cli arguments", e);
			System.exit(1);
		}

		if (cmd.hasOption("h") || args.length == 0) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("test", options);
			System.exit(0);
		}

		if(cmd.hasOption("l")) {
			Logger.getRootLogger().setLevel(Level.DEBUG);
		} else {
			Logger.getRootLogger().setLevel(Level.INFO);
		}

		serverCommand = new ServerCommandClientImpl();
		Campaign campaign = CampaignFactory.createBasicCampaign();

		String portString = cmd.getOptionValue("p");
		int portNumber;
		if (portString == null) {
			portNumber = 51234;
		} else {
			portNumber = Integer.parseInt(portString);
		}

		ServerConfig config = new ServerConfig("StandaloneServer",
				cmd.getOptionValue("g"),
				cmd.getOptionValue("a"),
				portNumber,
				cmd.getOptionValue("n"));

		ServerPolicy policy = new ServerPolicy();
		policy.setUseStrictTokenManagement(cmd.hasOption("s"));
		policy.setUseIndividualViews(cmd.hasOption("i"));
		policy.setPlayersReceiveCampaignMacros(cmd.hasOption("m"));
		policy.setUseToolTipsForDefaultRollFormat(cmd.hasOption("t"));
		policy.setRestrictedImpersonation(cmd.hasOption("r"));

		if (cmd.hasOption("e")) {
			policy.setPlayersCanRevealVision(true);
			policy.setAutoRevealOnMovement(true);
		} else {
			policy.setPlayersCanRevealVision(cmd.hasOption("v"));
			policy.setAutoRevealOnMovement(false);
		}
		
		try {
			startServer(null, config, policy, campaign);
		} catch (Exception e) {
			log.error("Could not start server", e);
			System.exit(1);
		}

		log.info("Started on port " + port);
		
		shutDownHandler = new ShutDownHandler();
		Runtime.getRuntime().addShutdownHook(shutDownHandler);
		
		if (cmd.hasOption("c")) {
			File campaignFile = new File(cmd.getOptionValue("c"));
			if (cmd.hasOption("o")) {
				shutDownHandler.setFile(campaignFile);
				log.warn("Save support is experimental!");
			}
			try {
				campaign = loadCampaignFile(campaignFile);
				log.info("Loaded campaign " + campaignFile.getAbsolutePath());
			} catch (IOException e) {
				log.error("Unable to load campaign", e);
			}
		}
		shutDownHandler.setCampaign(campaign);
	}
}
