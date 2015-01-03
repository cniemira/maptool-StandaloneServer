package net.rptools.maptool.server;

import java.io.IOException;

import net.rptools.maptool.client.AssetTransferHandler;
import net.rptools.maptool.client.MapToolRegistry;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignFactory;
import net.rptools.maptool.server.MapToolServer;
import net.rptools.maptool.server.ServerConfig;
import net.rptools.maptool.server.ServerPolicy;
import net.rptools.maptool.transfer.AssetTransferManager;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class StandaloneServer {
	private static final Logger log = Logger.getLogger(StandaloneServer.class);
	
	private static boolean haveProp(String name) {
		if(System.getProperty(name) != null) {
			return true;
		}
		return false;
		
	}
	
    private static void startServer(String id, ServerConfig config, ServerPolicy policy, Campaign campaign) throws IOException {
        AssetTransferManager assetTransferManager = new AssetTransferManager();
        assetTransferManager.addConsumerListener(new AssetTransferHandler());
        assetTransferManager.flush();

        MapToolServer server = new MapToolServer(config, policy);
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
    }
}
