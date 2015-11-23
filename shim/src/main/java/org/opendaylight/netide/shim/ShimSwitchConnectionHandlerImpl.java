/*
 * Copyright (c) 2015 NetIDE Consortium and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netide.shim;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import io.netty.buffer.ByteBuf;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.javatuples.Pair;
import org.opendaylight.netide.netiplib.HelloMessage;
import org.opendaylight.netide.netiplib.Protocol;
import org.opendaylight.netide.netiplib.ProtocolVersions;
import org.opendaylight.openflowjava.protocol.api.connection.ConnectionAdapter;
import org.opendaylight.openflowjava.protocol.api.connection.SwitchConnectionHandler;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.GetFeaturesInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.GetFeaturesOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.HelloInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.hello.Elements;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShimSwitchConnectionHandlerImpl implements SwitchConnectionHandler, ICoreListener, IHandshakeListener {
    public static final Long DEFAULT_XID = 0x01L;
    private static final Logger LOG = LoggerFactory.getLogger(ShimSwitchConnectionHandlerImpl.class);

    private static ZeroMQBaseConnector coreConnector;
    private ConnectionAdaptersRegistry connectionRegistry;
    private Pair<Protocol, ProtocolVersions> supportedProtocol;
    List<Pair<Protocol, ProtocolVersions>> supportedProtocols;
    private ShimRelay shimRelay;

    public ShimSwitchConnectionHandlerImpl(ZeroMQBaseConnector connector) {
        coreConnector = connector;
        supportedProtocol = null;
        supportedProtocols = new ArrayList<>();
    }

    public void init() {
        supportedProtocols.add(new Pair<Protocol, ProtocolVersions>(Protocol.OPENFLOW, ProtocolVersions.OPENFLOW_1_0));
        supportedProtocols.add(new Pair<Protocol, ProtocolVersions>(Protocol.OPENFLOW, ProtocolVersions.OPENFLOW_1_3));
        connectionRegistry = createConnectionAdaptersRegistry();
        connectionRegistry.init();
        shimRelay = createShimRelay();
    }

    public ShimRelay createShimRelay() {
        return new ShimRelay();
    }

    public ConnectionAdaptersRegistry createConnectionAdaptersRegistry() {
        return new ConnectionAdaptersRegistry();
    }

    @Override
    public boolean accept(InetAddress arg0) {
        return true;
    }

    @Override
    public void onSwitchConnected(ConnectionAdapter connectionAdapter) {
        LOG.info("SHIM: on Switch connected: {}", connectionAdapter.getRemoteAddress());
        ShimMessageListener listener = new ShimMessageListener(coreConnector, connectionAdapter, shimRelay, this);
        listener.registerConnectionAdaptersRegistry(connectionRegistry);
        listener.registerHandshakeListener(this);
        connectionRegistry.registerConnectionAdapter(connectionAdapter, null);
        connectionAdapter.setMessageListener(listener);
        connectionAdapter.setSystemListener(listener);
        connectionAdapter.setConnectionReadyListener(listener);
        handshake(connectionAdapter);
    }

    public void handshake(ConnectionAdapter connectionAdapter) {
        LOG.info("SHIM: OF Handshake Switch: {}", connectionAdapter.getRemoteAddress());
        HelloInputBuilder builder = new HelloInputBuilder();
        builder.setVersion((short) getMaxOFSupportedProtocol());
        builder.setXid(DEFAULT_XID);
        List<Elements> elements = new ArrayList<Elements>();
        builder.setElements(elements);
        connectionAdapter.hello(builder.build());
    }

    @Override
    public void onSwitchHelloMessage(long xid, Short version) {
        LOG.info("SHIM: OpenFlow hello message received. Xid: {}, OFVersion: {}", xid, version);
        byte received = version.byteValue();
        if (xid >= DEFAULT_XID) {
            if (received <= getMaxOFSupportedProtocol()) {
                LOG.info("SHIM: OpenFlow handshake agreed on version: {}", received);
                setSupportedProtocol(received);

            } else {
                LOG.info("SHIM: OpenFlow handshake agreed on version: {}", getMaxOFSupportedProtocol());
                setSupportedProtocol(getMaxOFSupportedProtocol());
            }
        }
    }

    public byte getMaxOFSupportedProtocol() {
        byte max = 0x00;
        for (Pair<Protocol, ProtocolVersions> protocols : this.supportedProtocols) {
            if (protocols.getValue0() == Protocol.OPENFLOW && protocols.getValue1().getValue() > max) {
                max = protocols.getValue1().getValue();
            }
        }
        return max;
    }

    public List<Byte> getSupportedOFProtocols() {
        List<Byte> results = new ArrayList<>();
        for (Pair<Protocol, ProtocolVersions> protocols : this.supportedProtocols) {
            if (protocols.getValue0() == Protocol.OPENFLOW) {
                results.add(protocols.getValue1().getValue());
            }
        }
        return results;
    }

    public int getNumberOfSwitches() {
        return this.connectionRegistry.getConnectionAdapters().size();
    }

    public Pair<Protocol, ProtocolVersions> getSupportedProtocol() {
        return this.supportedProtocol;
    }

    public void setSupportedProtocol(byte version) {
        this.supportedProtocol = new Pair<Protocol, ProtocolVersions>(Protocol.OPENFLOW,
                ProtocolVersions.parse(Protocol.OPENFLOW, version));
    }

    @Override
    public void onOpenFlowCoreMessage(Long datapathId, ByteBuf msg, int moduleId) {
        LOG.info("SHIM: OpenFlow Core message received");
        ConnectionAdapter conn = connectionRegistry.getConnectionAdapter(datapathId);
        if (conn != null) {
            short ofVersion = msg.readUnsignedByte();
            shimRelay.sendToSwitch(conn, msg, ofVersion, coreConnector, datapathId, moduleId);
        }
    }

    @Override
    public void onHelloCoreMessage(List<Pair<Protocol, ProtocolVersions>> requestedProtocols, int moduleId) {
        LOG.info("SHIM: Hello Core message received. Pair0: {}", requestedProtocols.get(0));
        for (Pair<Protocol, ProtocolVersions> requested : requestedProtocols) {
            if (requested.getValue0().getValue() == getSupportedProtocol().getValue0().getValue()
                    && requested.getValue1().getValue() == getSupportedProtocol().getValue1().getValue()) {
                LOG.info("SHIM: OF version matched");
                HelloMessage msg = new HelloMessage();
                msg.getSupportedProtocols().add(getSupportedProtocol());
                msg.getHeader().setPayloadLength((short) 2);
                msg.getHeader().setModuleId(moduleId);
                coreConnector.SendData(msg.toByteRepresentation());
                for (ConnectionAdapter conn : connectionRegistry.getConnectionAdapters()) {
                    LOG.info("SHIM: SendFeatures To core for switch: {}", conn.getRemoteAddress());
                    sendGetFeaturesToSwitch((short) getSupportedProtocol().getValue1().getValue(), DEFAULT_XID, conn,
                            moduleId);
                }
            }
        }
    }

    public void sendGetFeaturesOuputToCore(Future<RpcResult<GetFeaturesOutput>> switchReply,
            final Short proposedVersion, final int moduleId, final ConnectionAdapter connectionAdapter) {
        Futures.addCallback(JdkFutureAdapters.listenInPoolThread(switchReply),
                new FutureCallback<RpcResult<GetFeaturesOutput>>() {
                    @Override
                    public void onSuccess(RpcResult<GetFeaturesOutput> rpcFeatures) {
                        if (rpcFeatures.isSuccessful()) {
                            GetFeaturesOutput featureOutput = rpcFeatures.getResult();

                            LOG.info("obtained features: datapathId={}", featureOutput.getDatapathId());

                            // Register Switch connection/DatapathId to registry
                            connectionRegistry.registerConnectionAdapter(connectionAdapter,
                                    featureOutput.getDatapathId());
                            // Send Feature reply to Core
                            shimRelay.sendOpenFlowMessageToCore(ShimSwitchConnectionHandlerImpl.coreConnector,
                                    featureOutput, proposedVersion, featureOutput.getXid(),
                                    featureOutput.getDatapathId().shortValue(), moduleId);

                        } else {
                            // Handshake failed
                            for (RpcError rpcError : rpcFeatures.getErrors()) {
                                LOG.info("handshake - features failure [{}]: i:{} | m:{} | s:{}", rpcError.getInfo(),
                                        rpcError.getMessage(), rpcError.getSeverity(), rpcError.getCause());
                            }
                        }

                        LOG.info("postHandshake DONE");
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        LOG.info("getting feature failed seriously [addr:{}]: {}", connectionAdapter.getRemoteAddress(),
                                t.getMessage());
                    }
                });
    }

    public void sendGetFeaturesToSwitch(final Short proposedVersion, final Long xid,
            final ConnectionAdapter connectionAdapter, final int moduleId) {

        GetFeaturesInputBuilder featuresBuilder = new GetFeaturesInputBuilder();
        featuresBuilder.setVersion(proposedVersion).setXid(xid);

        Future<RpcResult<GetFeaturesOutput>> featuresFuture = connectionAdapter.getFeatures(featuresBuilder.build());

        sendGetFeaturesOuputToCore(featuresFuture, proposedVersion, moduleId, connectionAdapter);
        LOG.info("future features [{}] hooked ..", xid);
    }

}