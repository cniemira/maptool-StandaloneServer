This package contains a utility to bootstrap a standalone MapTool server.

To use, you need MapTool from rptools.net:
http://www.rptools.net/index.php?page=downloads#MapTool

Download a .zip file, extract, and then drop the provided standalone.jar file into this folder alongside
the maptool-1.3bXX.jar (as of this writing, b91 is current and is the only version this server has been
tested with). You can then start the standalone server like so:

    java -cp standalone.jar:maptool-1.3.bXX.jar -Dserver.port="51234" net.rptools.maptool.server.StandaloneServer

You can also rename (or better, `ln -s`) maptool-1.3.bXX.jar -> maptool-1.3.jar and run it this way:

	java -jar standalone.jar
  
You can declare any of the following properties to configure the server:

	server.name   (registers at rptools.net if set)
	server.port   (defaults to 51234)
	server.gmPassword
	server.playerPassword

You can also declare the following. The actual value doesn't matter, if you set them at all, they're on.

	log.debug
	server.useStrictTokenManagement
	server.playersCanRevealVision
	server.useIndividualViews
	server.playersReceiveCampaignMacros
	server.useToolTipsForDefaultRollFormat
	server.restrictedImpersonation


Something like this should do you fine:

	java -cp standalone.jar:maptool-1.3.b91.jar \
		-Dserver.gmPassword="secret" \
		-Dserver.playerPassword="******" \
		-Dserver.useStrictTokenManagement="on" \
		-Dserver.useIndividualViews="on" \
		-Dserver.playersReceiveCampaignMacros="on" \
		-Dserver.restrictedImpersonation="on" \
		net.rptools.maptool.server.StandaloneServer
