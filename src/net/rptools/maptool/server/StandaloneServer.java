package net.rptools.maptool.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

import net.rptools.lib.MD5Key;
import net.rptools.lib.ModelVersionManager;
import net.rptools.lib.io.PackedFile;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetTransferHandler;
import net.rptools.maptool.client.MapToolRegistry;
import net.rptools.maptool.client.ServerCommandClientImpl;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignFactory;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.transform.campaign.AssetNameTransform;
import net.rptools.maptool.model.transform.campaign.PCVisionTransform;
import net.rptools.maptool.server.MapToolServer;
import net.rptools.maptool.server.ServerConfig;
import net.rptools.maptool.server.ServerPolicy;
import net.rptools.maptool.transfer.AssetTransferManager;
import net.rptools.maptool.util.PersistenceUtil.PersistedCampaign;
import net.rptools.maptool.util.StringUtil;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class StandaloneServer {
	private static final Logger log = Logger.getLogger(StandaloneServer.class);
	
	private static final ModelVersionManager campaignVersionManager = new ModelVersionManager();
	private static final ModelVersionManager assetnameVersionManager = new ModelVersionManager();
	
	private static MapToolServer server;
	private static ServerCommand serverCommand;
	
	static {
		PackedFile.init(AppUtil.getAppHome("tmp"));
		campaignVersionManager.registerTransformation("1.3.51", new PCVisionTransform());
		assetnameVersionManager.registerTransformation("1.3.51", new AssetNameTransform("^(.*)\\.(dat)?$", "$1"));
	}
		
	private static boolean haveProp(String name) {
		if(System.getProperty(name) != null) {
			return true;
		}
		return false;
		
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
				Asset asset;
				asset = (Asset) pakFile.getFileObject(pathname);
				
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
	
	public static void loadCampaignFile(String filename) throws IOException {
		File campaignFile = new File(filename);
		PackedFile pakfile = new PackedFile(campaignFile);
		pakfile.setModelVersionManager(campaignVersionManager);
		
		String version = (String)pakfile.getProperty("campaignVersion");
		version = version == null ? "1.3.50" : version;
		PersistedCampaign persistedCampaign = (PersistedCampaign) pakfile.getContent(version);

		if (persistedCampaign != null) {
			server.setCampaign(persistedCampaign.campaign);
			
			Set<MD5Key> allAssetIds = persistedCampaign.assetMap.keySet();
			loadAssets(allAssetIds, pakfile);
			for (Zone zone : persistedCampaign.campaign.getZones()) {
				zone.optimize();
			}
		}
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
    	
    	if(haveProp("log.debug")) {
    		Logger.getRootLogger().setLevel(Level.DEBUG);
    	} else {
    		Logger.getRootLogger().setLevel(Level.INFO);
    	}
    	
    	serverCommand = new ServerCommandClientImpl();
    	Campaign campaign = CampaignFactory.createBasicCampaign();
    	
    	String port_number = System.getProperty("server.port");
    	int port;
    	if (port_number == null) {
    		port = 51234;
    	} else {
    		port = Integer.parseInt(port_number);
    	}

    	ServerConfig config = new ServerConfig("StandaloneServer",
        		System.getProperty("server.gmPassword"),
        		System.getProperty("server.playerPassword"),
        		port,
        		System.getProperty("server.name"));

        ServerPolicy policy = new ServerPolicy();        
        policy.setUseStrictTokenManagement(haveProp("server.useStrictTokenManagement"));
        policy.setPlayersCanRevealVision(haveProp("server.playersCanRevealVision"));
        policy.setUseIndividualViews(haveProp("server.useIndividualViews"));
        policy.setPlayersReceiveCampaignMacros(haveProp("server.playersReceiveCampaignMacros"));
        policy.setUseToolTipsForDefaultRollFormat(haveProp("server.useToolTipsForDefaultRollFormat"));
        policy.setRestrictedImpersonation(haveProp("server.restrictedImpersonation"));

        try {
        	startServer(null, config, policy, campaign);
        } catch (Exception e) {
        	log.error("Could not start server", e);
        	System.exit(1);
        }
        
        log.info("Started on port " + port);
        
    	if (haveProp("campaign.file")) {
    		try {
				loadCampaignFile(System.getProperty("campaign.file"));
			} catch (IOException e) {
				log.error("Unable to load campaign", e);
			}
    	}
    }
}
