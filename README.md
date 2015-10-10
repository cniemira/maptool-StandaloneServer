This package contains a utility to bootstrap a standalone MapTool server.

To use, you need MapTool from rptools.net:
http://www.rptools.net/index.php?page=downloads#MapTool

Download a .zip file, extract, and then drop the provided standalone.jar and the files in the lib folder
into this folder alongside the maptool-1.3bXX.jar (as of this writing, b91 is current and is the only
version this server has been tested with).

You can then start the standalone server like so:

    java -cp standalone.jar:maptool-1.3.bXX.jar -port="51234"

You can also rename (or better, `ln -s`) maptool-1.3.bXX.jar -> maptool.jar and run it this way:

	java -jar standalone.jar

You can declare any of the following properties to configure the server:

	name   (registers at rptools.net if set)
	port   (defaults to 51234)
	gmPassword
	playerPassword
	campaign

Because the MapTool server must host a saved campaign, you load a .cmpgn file in the standalone server, but keep in mind that changes will have to be saved on one of the clients. You can also declare the following.

	logDebug
	useStrictTokenManagement
	playersCanRevealVision
	autoRevealOnMovement (implies playersCanRevealVision)
	useIndividualViews
	playersReceiveCampaignMacros
	useToolTipsForDefaultRollFormat
	restrictedImpersonation

You can get a help with all possible arguments simply by calling

	java -jar standalone.jar

Something like this should do you fine:

	java -jar standalone.jar \
		--gmPassword="secret" \
		--playerPassword="******" \
		--useStrictTokenManagement \
		--useIndividualViews \
		--autoRevealOnMovement \
		--playersReceiveCampaignMacros \
		--restrictedImpersonation
