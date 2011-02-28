/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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
package org.jboss.as.controller.remote;

import static org.jboss.as.protocol.ProtocolUtils.expectHeader;
import static org.jboss.as.protocol.StreamUtils.readByte;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.as.controller.Cancellable;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationResult;
import org.jboss.as.controller.ResultHandler;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.ModelControllerClientProtocol;
import org.jboss.as.protocol.Connection;
import org.jboss.as.protocol.MessageHandler;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.protocol.mgmt.AbstractMessageHandler;
import org.jboss.as.protocol.mgmt.ManagementProtocol;
import org.jboss.as.protocol.mgmt.ManagementResponse;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public class ModelControllerOperationHandlerImpl extends AbstractMessageHandler implements ModelControllerOperationHandler {

    private static final Logger log = Logger.getLogger("org.jboss.server.management");

    private final ModelControllerClient.Type type;

    private final ModelController modelController;

    private final AtomicInteger currentAsynchronousRequestId = new AtomicInteger();

    private final Map<Integer, Cancellable> asynchOperations = Collections.synchronizedMap(new HashMap<Integer, Cancellable>());

    private final MessageHandler initiatingHandler;

    protected ModelControllerOperationHandlerImpl(ModelControllerClient.Type type, ModelController modelController, MessageHandler initiatingHandler) {
        this.type = type;
        this.modelController = modelController;
        this.initiatingHandler = initiatingHandler;
    }

    /** {@inheritDoc} */
    @Override
    public void handle(Connection connection, InputStream input) throws IOException {
        expectHeader(input, ManagementProtocol.REQUEST_OPERATION);
        final byte commandCode = readByte(input);

        final AbstractMessageHandler operation = operationFor(commandCode);
        if (operation == null) {
            throw new IOException("Invalid command code " + commandCode + " received from standalone client");
        }
        log.debugf("Received operation [%s]", operation);

        operation.handle(connection, input);
    }

    /** {@inheritDoc} */
    @Override
    public byte getIdentifier() {
        return type.getHandlerId();
    }

    protected ModelController getController() {
        return modelController;
    }

    protected MessageHandler getInitiatingHandler() {
        return initiatingHandler;
    }

    public ManagementResponse operationFor(final byte commandByte) {
        switch (commandByte) {
            case ModelControllerClientProtocol.EXECUTE_ASYNCHRONOUS_REQUEST:
                return new ExecuteAsynchronousOperation();
            case ModelControllerClientProtocol.EXECUTE_SYNCHRONOUS_REQUEST:
                return new ExecuteSynchronousOperation();
            case ModelControllerClientProtocol.CANCEL_ASYNCHRONOUS_OPERATION_REQUEST:
                return new CancelAsynchronousOperation();
            default:
                return null;
        }
    }

    private ModelNode readNode(InputStream in) throws IOException {
        ModelNode node = new ModelNode();
        node.readExternal(in);
        return node;
    }

    private abstract class ExecuteOperation extends ManagementResponse {
        ModelNode operation;

        ExecuteOperation() {
            super(getInitiatingHandler());
        }

        @Override
        protected final void readRequest(final InputStream inputStream) throws IOException {
            expectHeader(inputStream, ModelControllerClientProtocol.PARAM_OPERATION);
            operation = readNode(inputStream);
        }
    }

    private class ExecuteSynchronousOperation extends ExecuteOperation {
        @Override
        protected final byte getResponseCode() {
            return ModelControllerClientProtocol.EXECUTE_SYNCHRONOUS_RESPONSE;
        }

        @Override
        protected void sendResponse(final OutputStream outputStream) throws IOException {
            ModelNode result = modelController.execute(operation);
            outputStream.write(ModelControllerClientProtocol.PARAM_OPERATION);
            result.writeExternal(outputStream);
        }
    }

    private class ExecuteAsynchronousOperation extends ExecuteOperation {

        final int asynchronousRequestId = currentAsynchronousRequestId.incrementAndGet();

        @Override
        protected final byte getResponseCode() {
            return ModelControllerClientProtocol.EXECUTE_ASYNCHRONOUS_RESPONSE;
        }

        @Override
        protected void sendResponse(final OutputStream outputStream) throws IOException {
            final CountDownLatch completeLatch = new CountDownLatch(1);
            final IOExceptionHolder exceptionHolder = new IOExceptionHolder();
            final FailureHolder failureHolder = new FailureHolder();
            final AtomicInteger status = new AtomicInteger(0);

            OperationResult result = modelController.execute(operation, new ResultHandler() {
                @Override
                public void handleResultFragment(String[] location, ModelNode fragment) {
                    try {
                        synchronized (outputStream) {
                            outputStream.write(ModelControllerClientProtocol.PARAM_HANDLE_RESULT_FRAGMENT);
                            outputStream.write(ModelControllerClientProtocol.PARAM_LOCATION);
                            StreamUtils.writeInt(outputStream, location.length);
                            for (String loc : location) {
                                StreamUtils.writeUTFZBytes(outputStream, loc);
                            }
                            outputStream.write(ModelControllerClientProtocol.PARAM_OPERATION);
                            fragment.writeExternal(outputStream);
                            outputStream.flush();
                        }
                    } catch (IOException e) {
                        asynchOperations.remove(asynchronousRequestId);
                        exceptionHolder.setException(e);
                        completeLatch.countDown();
                    }
                }

                @Override
                public void handleResultComplete() {
                    asynchOperations.remove(asynchronousRequestId);
                    if(!status.compareAndSet(0, 1)) {
                        throw new RuntimeException("Result already set");
                    }
                    completeLatch.countDown();
                }

                @Override
                public void handleFailed(final ModelNode failureDescription) {
                    asynchOperations.remove(asynchronousRequestId);
                    if(!status.compareAndSet(0, 2)) {
                        throw new RuntimeException("Result already set");
                    }
                    failureHolder.setFailure(failureDescription);
                    completeLatch.countDown();
                }

                @Override
                public void handleCancellation() {
                    asynchOperations.remove(asynchronousRequestId);
                    if(!status.compareAndSet(0, 3)) {
                        throw new RuntimeException("Result already set");
                    }
                    completeLatch.countDown();
                }
            });

            if (completeLatch.getCount() == 0) {
                //It was handled synchronously or has completed by now
            } else {
                //It was handled asynchronously
                asynchOperations.put(Integer.valueOf(asynchronousRequestId), result.getCancellable());
                synchronized (outputStream) {
                    outputStream.write(ModelControllerClientProtocol.PARAM_REQUEST_ID);
                    StreamUtils.writeInt(outputStream, asynchronousRequestId);
                    outputStream.flush();
                }

                while (true) {
                    try {
                        completeLatch.await();
                        break;
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (exceptionHolder.getException() != null) {
                throw exceptionHolder.getException();
            }

            switch (status.get()) {
                case 1: {
                    synchronized (outputStream) {
                        outputStream.write(ModelControllerClientProtocol.PARAM_HANDLE_RESULT_COMPLETE);
                        outputStream.write(ModelControllerClientProtocol.PARAM_OPERATION);
                        result.getCompensatingOperation().writeExternal(outputStream);
                        outputStream.flush();
                    }
                    break;
                }
                case 2: {
                    synchronized (outputStream) {
                        outputStream.write(ModelControllerClientProtocol.PARAM_HANDLE_RESULT_FAILED);
                        outputStream.write(ModelControllerClientProtocol.PARAM_OPERATION);
                        failureHolder.getFailure().writeExternal(outputStream);
                        outputStream.flush();
                    }
                    break;
                }
                case 3: {
                    synchronized (outputStream) {
                        outputStream.write(ModelControllerClientProtocol.PARAM_HANDLE_CANCELLATION);
                        outputStream.flush();
                    }
                    break;
                }
                default: {
                    throw new IOException("Unknown status type " + status.get());
                }
            }
        }
    }

    private class CancelAsynchronousOperation extends ManagementResponse {

        private boolean cancelled;
        @Override
        protected final byte getResponseCode() {
            return ModelControllerClientProtocol.CANCEL_ASYNCHRONOUS_OPERATION_RESPONSE;
        }

        @Override
        protected final void readRequest(final InputStream inputStream) throws IOException {

            expectHeader(inputStream, ModelControllerClientProtocol.PARAM_REQUEST_ID);
            int operationId = StreamUtils.readInt(inputStream);

            Cancellable operation = asynchOperations.get(Integer.valueOf(operationId));
            cancelled = operation!= null && operation.cancel();
        }

        @Override
        protected void sendResponse(final OutputStream outputStream) throws IOException {
            StreamUtils.writeBoolean(outputStream, cancelled);
        }
    }

    private final class FailureHolder {
        ModelNode failure;

        public ModelNode getFailure() {
            return failure;
        }

        public void setFailure(ModelNode failure) {
            this.failure = failure;
        }
    }

    private final class IOExceptionHolder {
        IOException exception;

        public IOException getException() {
            return exception;
        }

        public void setException(IOException exception) {
            this.exception = exception;
        }
    }

    @Override
    public String toString() {
        return super.toString() + "[" + type + "]";
    }
}
