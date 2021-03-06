/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.host.controller;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DESCRIBE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.protocol.ProtocolUtils.expectHeader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.remote.ModelControllerOperationHandler;
import org.jboss.as.domain.controller.FileRepository;
import org.jboss.as.host.controller.mgmt.DomainControllerProtocol;
import org.jboss.as.protocol.Connection;
import org.jboss.as.protocol.MessageHandler;
import org.jboss.as.protocol.ProtocolClient;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.protocol.mgmt.ManagementHeaderMessageHandler;
import org.jboss.as.protocol.mgmt.ManagementRequest;
import org.jboss.as.protocol.mgmt.ManagementRequestConnectionStrategy;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

/**
 * @author Kabir Khan
 */
class RemoteDomainConnectionService implements DomainControllerConnection, Service<DomainControllerConnection> {

    private static final int CONNECTION_TIMEOUT = 5000;
    private final InetAddress host;
    private final int port;
    private final String name;

    private volatile Connection connection;
    private volatile ModelControllerClient client;
    private volatile ModelControllerOperationHandler operationHandler;

    RemoteDomainConnectionService(String name, InetAddress host, int port){
        this.name = name;
        this.host = host;
        this.port = port;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized ModelNode register(final HostController hostController) {
        final ProtocolClient.Configuration config = new ProtocolClient.Configuration();
        config.setMessageHandler(initialMessageHandler);
        config.setConnectTimeout(CONNECTION_TIMEOUT);
        config.setReadExecutor(Executors.newCachedThreadPool()); //TODO inject
        config.setSocketFactory(SocketFactory.getDefault());
        config.setServerAddress(new InetSocketAddress(host, port));
        config.setThreadFactory(Executors.defaultThreadFactory()); //TODO inject

        final ProtocolClient protocolClient = new ProtocolClient(config);

        try {
            connection = protocolClient.connect();
            client = ModelControllerClient.Factory.create(ModelControllerClient.Type.DOMAIN, connection);
            operationHandler = ModelControllerOperationHandler.Factory.create(ModelControllerClient.Type.HOST, hostController, initialMessageHandler);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            return new RegisterModelControllerRequest().executeForResult(new ManagementRequestConnectionStrategy.ExistingConnectionStrategy(connection));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ModelNode getProfileOperations(String profileName) {
        assert profileName != null : "Null profile name";
        ModelNode operation = new ModelNode();

        operation.get(OP).set(DESCRIBE);
        operation.get(OP_ADDR).set(PathAddress.pathAddress(PathElement.pathElement(PROFILE, profileName)).toModelNode());
        try {
            return client.execute(operation).require(RESULT);
        } catch (CancellationException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void unregister() {
        try {
            new UnregisterModelControllerRequest().executeForResult(new ManagementRequestConnectionStrategy.ExistingConnectionStrategy(connection));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized FileRepository getRemoteFileRepository() {
        // TODO Auto-generated method stub
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void start(StartContext context) throws StartException {
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void stop(StopContext context) {
        StreamUtils.safeClose(client);
        StreamUtils.safeClose(connection);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized DomainControllerConnection getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    private abstract class RegistryRequest<T> extends ManagementRequest<T> {

        @Override
        protected byte getHandlerId() {
            return ModelControllerClient.Type.DOMAIN.getHandlerId();
        }
    }

    private class RegisterModelControllerRequest extends RegistryRequest<ModelNode> {
        @Override
        protected byte getRequestCode() {
            return DomainControllerProtocol.REGISTER_HOST_CONTROLLER_REQUEST;
        }

        @Override
        protected byte getResponseCode() {
            return DomainControllerProtocol.REGISTER_HOST_CONTROLLER_RESPONSE;
        }

        /** {@inheritDoc} */
        @Override
        protected void sendRequest(final int protocolVersion, final OutputStream output) throws IOException {
            output.write(DomainControllerProtocol.PARAM_HOST_ID);
            StreamUtils.writeUTFZBytes(output, name);
        }

        /** {@inheritDoc} */
        @Override
        protected ModelNode receiveResponse(InputStream input) throws IOException {
            expectHeader(input, DomainControllerProtocol.PARAM_MODEL);
            ModelNode node = new ModelNode();
            node.readExternal(input);
            return node;
        }
    }

    private class UnregisterModelControllerRequest extends RegistryRequest<Void> {
        @Override
        protected byte getRequestCode() {
            return DomainControllerProtocol.UNREGISTER_HOST_CONTROLLER_REQUEST;
        }

        @Override
        protected byte getResponseCode() {
            return DomainControllerProtocol.UNREGISTER_HOST_CONTROLLER_RESPONSE;
        }

        /** {@inheritDoc} */
        @Override
        protected void sendRequest(final int protocolVersion, final OutputStream output) throws IOException {
            output.write(DomainControllerProtocol.PARAM_HOST_ID);
            StreamUtils.writeUTFZBytes(output, name);
        }
    }

    private final MessageHandler initialMessageHandler = new ManagementHeaderMessageHandler() {

        @Override
        public void handle(Connection connection, InputStream dataStream) throws IOException {
            super.handle(connection, dataStream);
        }

        @Override
        protected MessageHandler getHandlerForId(byte handlerId) {
            if (handlerId == ModelControllerClient.Type.HOST.getHandlerId()) {
                return operationHandler;
            }
            return null;
        }
    };

}
