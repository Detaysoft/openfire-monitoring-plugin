package com.reucon.openfire.plugin.archive.xep0313;

import com.reucon.openfire.plugin.archive.ArchiveProperties;
import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import com.reucon.openfire.plugin.archive.xep.AbstractIQHandler;
import com.reucon.openfire.plugin.archive.xep0059.XmppResultSet;
import org.dom4j.*;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.PacketRouter;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.disco.ServerFeaturesProvider;
import org.jivesoftware.openfire.forward.Forwarded;
import org.jivesoftware.openfire.muc.MUCRole;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.NamedThreadFactory;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.PacketError;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * XEP-0313 IQ Query Handler
 */
abstract class IQQueryHandler extends AbstractIQHandler implements
        ServerFeaturesProvider {

    private static final Logger Log = LoggerFactory.getLogger(IQQueryHandler.class);
    protected final String NAMESPACE;
    protected ExecutorService executorService;
    protected PacketRouter router;

    private final XMPPDateTimeFormat xmppDateTimeFormat = new XMPPDateTimeFormat();

    IQQueryHandler(final String moduleName, final String namespace) {
        super(moduleName, "query", namespace);
        NAMESPACE = namespace;
    }

    @Override
    public void initialize( XMPPServer server )
    {
        super.initialize( server );
        executorService = Executors.newCachedThreadPool( new NamedThreadFactory( "message-archive-handler-", null, null, null ) );
        router = server.getPacketRouter();
    }

    @Override
    public void stop()
    {
        executorService.shutdown();
        super.stop();
    }

    @Override
    public void destroy()
    {
        // Give the executor some time to finish processing jobs.
        final long end = System.currentTimeMillis() + 4000;
        while ( !executorService.isTerminated() && System.currentTimeMillis() < end )
        {
            try
            {
                Thread.sleep( 100 );
            }
            catch ( InterruptedException e )
            {
                break;
            }
        }
        executorService.shutdownNow();
        super.destroy();
    }

    public IQ handleIQ( final IQ packet ) throws UnauthorizedException {

        if(packet.getType().equals(IQ.Type.get)) {
            return buildSupportedFieldsResult(packet);
        }

        // Default to user's own archive
        JID archiveJid = packet.getTo();
        if (archiveJid == null) {
            archiveJid = packet.getFrom().asBareJID();
        }
        Log.debug("Archive requested is {}", archiveJid);

        // Now decide the type.
        boolean muc = false;
        if (!XMPPServer.getInstance().isLocal(archiveJid)) {
            Log.debug("Archive is not local (user)");
            if (XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(archiveJid) == null) {
                Log.debug("No chat service for this domain");
                return buildErrorResponse(packet);
            } else {
                muc = true;
                Log.debug("MUC");
            }
        }

        JID requestor = packet.getFrom().asBareJID();
        Log.debug("Requestor is {} for muc=={}", requestor, muc);

        // Auth checking.
        if(muc) {
            MultiUserChatService service = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(archiveJid);
            MUCRoom room = service.getChatRoom(archiveJid.getNode());
            if (room == null) {
                Log.debug("Unable to process query as room name '{}' is not recognized.", archiveJid);
                return buildErrorResponse(packet);
            }
            boolean pass = false;
            if (service.isSysadmin(requestor)) {
                pass = true;
            }
            MUCRole.Affiliation aff =  room.getAffiliation(requestor);
            if (aff != MUCRole.Affiliation.outcast) {
                if (aff == MUCRole.Affiliation.owner || aff == MUCRole.Affiliation.admin) {
                    pass = true;
                } else if (room.isMembersOnly()) {
                    if (aff == MUCRole.Affiliation.member) {
                        pass = true;
                    }
                } else {
                    pass = true;
                }
            }
            if (!pass) {
                Log.debug("Unable to process query as requestor '{}' is forbidden to retrieve archive for room '{}'.", requestor, archiveJid);
                return buildForbiddenResponse(packet);
            }

            // Password protected room
            if (room.isPasswordProtected())  {
                // check whether requestor is occupant in the room
                MUCRole occupant = room.getOccupantByFullJID(packet.getFrom());

                if (occupant == null) {
                    // no occupant so currently not authenticated to query archive
                    Log.debug("Unable to process query as requestor '{}' is currently not authenticated for this password protected room '{}'.", requestor, archiveJid);
                    return buildForbiddenResponse(packet);
                }
            }
        } else if(!archiveJid.equals(requestor)) { // Not user's own
            // ... disallow unless admin.
            if (!XMPPServer.getInstance().getAdmins().contains(requestor)) {
                Log.debug("Unable to process query as requestor '{}' is forbidden to retrieve personal archives other than his own. Unable to access archives of '{}'.", requestor, archiveJid);
                return buildForbiddenResponse(packet);
            }
        }

        sendMidQuery(packet);

        if ( JiveGlobals.getBooleanProperty( ArchiveProperties.FORCE_RSM, true ) ) {
            final QName seQName = QName.get( "set", XmppResultSet.NAMESPACE);
            if ( packet.getChildElement().element(seQName ) == null ) {
                packet.getChildElement().addElement( seQName );
            }
        }
        final QueryRequest queryRequest = new QueryRequest(packet.getChildElement(), archiveJid);

        // OF-1200: make sure that data is flushed to the database before retrieving it.
        final MonitoringPlugin plugin = (MonitoringPlugin) XMPPServer.getInstance().getPluginManager().getPlugin(MonitoringConstants.NAME);
        final ConversationManager conversationManager = (ConversationManager)plugin.getModule( ConversationManager.class);
        final Instant targetEndDate = Instant.now(); // TODO or, the timestamp of the element referenced by 'before' from RSM, if that's set.

        executorService.submit( () -> {
            try
            {
                Log.debug("Retrieving messages from archive...");
                Duration eta;
                while ( !(eta = conversationManager.availabilityETA( targetEndDate )).isZero() )
                {
                    try
                    {
                        Log.trace( "Not all data that is being requested has been written to the database yet. Delaying request processing for {}", eta );
                        Thread.sleep( eta.toMillis() );
                    }
                    catch ( InterruptedException e )
                    {
                        Log.warn( "Interrupted wait for data availability. Data might be incomplete!", e );
                        break;
                    }
                }
                Log.debug( "All data that has been requested has been written to the database. Proceed to process request." );

                Collection<ArchivedMessage> archivedMessages = retrieveMessages(queryRequest);
                Log.debug("Retrieved {} messages from archive.", archivedMessages.size());

                for(ArchivedMessage archivedMessage : archivedMessages) {
                    sendMessageResult(packet.getFrom(), queryRequest, archivedMessage);
                }

                sendEndQuery(packet, packet.getFrom(), queryRequest);
                Log.debug("Done with request.");
            }
            catch ( Exception e ) {
                Log.error( "An unexpected exception occurred while processing: {}", packet, e );
                if (packet.isRequest()) {
                    try {
                        router.route( buildErrorResponse( packet ) );
                    } catch ( Exception ex ) {
                        Log.error( "An unexpected exception occurred while returning an error stanza to the originator of: {}", packet, ex );
                    }
                }
            }
        } );

        return null;
    }

    protected void sendMidQuery(IQ packet) {
        // Default: Do nothing.
    }

    protected abstract void sendEndQuery(IQ packet, JID from, QueryRequest queryRequest);

    /**
     * Create error response to send to client
     * @param packet IQ stanza received
     * @return IQ stanza to be sent.
     */
    private IQ buildErrorResponse(IQ packet) {
        IQ reply = IQ.createResultIQ(packet);
        reply.setChildElement(packet.getChildElement().createCopy());
        reply.setError(PacketError.Condition.internal_server_error);
        return reply;
    }

    /**
     * Create error response due to forbidden request
     * @param packet Received request
     * @return
     */
    private IQ buildForbiddenResponse(IQ packet) {
        IQ reply = IQ.createResultIQ(packet);
        reply.setChildElement(packet.getChildElement().createCopy());
        reply.setError(PacketError.Condition.forbidden);
        return reply;
    }

    /**
     * Retrieve messages matching query request from server archive
     * @param queryRequest The request (cannot be null).
     * @return A collection of messages (possibly empty, never null).
     */
    private Collection<ArchivedMessage> retrieveMessages(QueryRequest queryRequest) {

        String withField = null;
        String startField = null;
        String endField = null;
        DataForm dataForm = queryRequest.getDataForm();
        if(dataForm != null) {
            if(dataForm.getField("with") != null) {
                withField = dataForm.getField("with").getFirstValue();
            }
            if(dataForm.getField("start") != null) {
                startField = dataForm.getField("start").getFirstValue();
            }
            if(dataForm.getField("end") != null) {
                endField = dataForm.getField("end").getFirstValue();
            }
        }

        Date startDate = null;
        Date endDate = null;
        try {
            if(startField != null) {
                startDate = xmppDateTimeFormat.parseString(startField);
            }
            if(endField != null) {
                endDate = xmppDateTimeFormat.parseString(endField);
            }
        } catch (ParseException e) {
            Log.error("Error parsing query date filters.", e);
        }

        return getPersistenceManager(queryRequest.getArchive()).findMessages(
                startDate,
                endDate,
                queryRequest.getArchive().toBareJID(),
                withField,
                queryRequest.getResultSet(),
                this.usesUniqueAndStableIDs());
    }

    /**
     * Defines if the implementation uses XEP-0359-defined 'unique and stable'
     * stanza identifiers. MAM2 introduced a dependency on this new feature.
     *
     * @return true if the implementation uses XEP-0359, otherwise, false.
     */
    abstract boolean usesUniqueAndStableIDs();

    /**
     * Send result packet to client acknowledging query.
     * @param packet Received query packet
     */
    private void sendAcknowledgementResult(IQ packet) {
        IQ result = IQ.createResultIQ(packet);
        router.route(result);
    }

    /**
     * Send final message back to client following query.
     * @param from to respond to
     * @param queryRequest Received query request
     */
    private void sendFinalMessage(JID from, final QueryRequest queryRequest) {

        Message finalMessage = new Message();
        finalMessage.setTo(from);
        Element fin = finalMessage.addChildElement("fin", NAMESPACE);
        if(queryRequest.getQueryid() != null) {
            fin.addAttribute("queryid", queryRequest.getQueryid());
        }

        XmppResultSet resultSet = queryRequest.getResultSet();
        if (resultSet != null) {
            fin.add(resultSet.createResultElement());

            if(resultSet.isComplete()) {
                fin.addAttribute("complete", "true");
            }
        }

        router.route(finalMessage);
    }

    /**
     * Send archived message to requesting client
     * @param from to recieve message
     * @param queryRequest Query request made by client
     * @param archivedMessage Message to send to client
     * @return
     */
    private void sendMessageResult(JID from, QueryRequest queryRequest, ArchivedMessage archivedMessage) {

        String stanzaText = archivedMessage.getStanza();
        if(stanzaText == null || stanzaText.equals("")) {
            // Try creating a fake one from the body.
            if (archivedMessage.getBody() != null && !archivedMessage.getBody().equals("")) {
                stanzaText = String.format("<message from=\"%s\" to=\"%s\" type=\"chat\"><body>%s</body>", archivedMessage.getWithJid(), archivedMessage.getWithJid(), archivedMessage.getBody());
            } else {
                // Don't send legacy archived messages (that have no stanza)
                return;
            }
        }

        Message messagePacket = new Message();
        messagePacket.setTo(from);
        if ( XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService( queryRequest.getArchive() ) != null )
        {
            messagePacket.setFrom( queryRequest.getArchive().asBareJID() );
        }
        Forwarded fwd;

        Document stanza;
        try {
            stanza = DocumentHelper.parseText(stanzaText);
            fwd = new Forwarded(stanza.getRootElement(), archivedMessage.getTime(), null);
        } catch (DocumentException e) {
            Log.error("Failed to parse message stanza.", e);
            // If we can't parse stanza then we have no message to send to client, abort
            return;
        }

        if (fwd == null) return; // Shouldn't be possible.

        messagePacket.addExtension(new Result(fwd, NAMESPACE, queryRequest.getQueryid(), archivedMessage.getId().toString()));
        router.route(messagePacket);
    }

    /**
     * Declare DataForm fields supported by the MAM implementation on this server
     * @param packet Incoming query (form field request) packet
     */
    private IQ buildSupportedFieldsResult(IQ packet) {

        IQ result = IQ.createResultIQ(packet);

        Element query = result.setChildElement("query", NAMESPACE);

        DataForm form = new DataForm(DataForm.Type.form);
        form.addField("FORM_TYPE", null, FormField.Type.hidden);
        form.getField("FORM_TYPE").addValue(NAMESPACE);
        form.addField("with", null, FormField.Type.jid_single);
        form.addField("start", null, FormField.Type.text_single);
        form.addField("end", null, FormField.Type.text_single);

        query.add(form.getElement());

        return result;
    }

    @Override
    public Iterator<String> getFeatures() {
        return Collections.singleton(NAMESPACE).iterator();
    }

    void completeFinElement(QueryRequest queryRequest, Element fin) {
        if(queryRequest.getQueryid() != null) {
            fin.addAttribute("queryid", queryRequest.getQueryid());
        }

        XmppResultSet resultSet = queryRequest.getResultSet();
        if (resultSet != null) {
            fin.add(resultSet.createResultElement());

            if(resultSet.isComplete()) {
                fin.addAttribute("complete", "true");
            }
        }
    }
}
