/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.nio.transport;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.logging.Logger;
import org.glassfish.grizzly.*;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.ReadyFutureImpl;
import org.glassfish.grizzly.nio.NIOChannelDistributor;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.RegisterChannelResult;
import org.glassfish.grizzly.utils.Futures;

/**
 * UDP NIO transport client side ConnectorHandler implementation
 * 
 * @author Alexey Stashok
 */
public class UDPNIOConnectorHandler extends AbstractSocketConnectorHandler {

    private static final Logger LOGGER = Grizzly.logger(UDPNIOConnectorHandler.class);

    protected boolean isReuseAddress;

    protected UDPNIOConnectorHandler(UDPNIOTransport transport) {
        super(transport);
        isReuseAddress = transport.isReuseAddress();
    }

    /**
     * Creates non-connected UDP {@link Connection}.
     *
     * @return non-connected UDP {@link Connection}.
     */
    public GrizzlyFuture<Connection> connect() {
        return connect0(null, null, null, true);
    }

    @Override
    public void connect(final SocketAddress remoteAddress,
            final SocketAddress localAddress,
            final CompletionHandler<Connection> completionHandler) {

        connect0(remoteAddress, localAddress, completionHandler, false);
    }

    @Override
    protected final FutureImpl<Connection> connect0(
            final SocketAddress remoteAddress,
            final SocketAddress localAddress,
            final CompletionHandler<Connection> completionHandler,
            final boolean needFuture) {

        final UDPNIOTransport nioTransport = (UDPNIOTransport) transport;
        UDPNIOConnection newConnection = null;

        try {
            final DatagramChannel datagramChannel =
                    nioTransport.getSelectorProvider().openDatagramChannel();
            final DatagramSocket socket = datagramChannel.socket();

            newConnection = nioTransport.obtainNIOConnection(datagramChannel);

            socket.setReuseAddress(isReuseAddress);

            if (localAddress != null) {
                socket.bind(localAddress);
            }

            datagramChannel.configureBlocking(false);

            if (remoteAddress != null) {
                datagramChannel.connect(remoteAddress);
            }

            preConfigure(newConnection);

            final NIOChannelDistributor nioChannelDistributor =
                    nioTransport.getNIOChannelDistributor();

            if (nioChannelDistributor == null) {
                throw new IllegalStateException(
                        "NIOChannelDistributor is null. Is Transport running?");
            }
            
            final CompletionHandler<Connection> completionHandlerToPass;
            final FutureImpl<Connection> futureToReturn;
            
            if (needFuture) {
                futureToReturn = makeCancellableFuture(newConnection);
                
                completionHandlerToPass = Futures.toCompletionHandler(
                        futureToReturn, completionHandler);
                
            } else {
                completionHandlerToPass = completionHandler;
                futureToReturn = null;
            }

            // if connected immediately - register channel on selector with NO_INTEREST
            // interest
            nioChannelDistributor.registerChannelAsync(datagramChannel,
                    0, newConnection,
                    new ConnectHandler(newConnection, completionHandlerToPass));
            
            return futureToReturn;
        } catch (Exception e) {
            if (newConnection != null) {
                newConnection.closeSilently();
            }

            if (completionHandler != null) {
                completionHandler.failed(e);
            }
            
            return needFuture ? ReadyFutureImpl.<Connection>create(e) : null;
        }

    }

    public boolean isReuseAddress() {
        return isReuseAddress;
    }

    public void setReuseAddress(boolean isReuseAddress) {
        this.isReuseAddress = isReuseAddress;
    }

    private static void abortConnection(final UDPNIOConnection connection,
            final CompletionHandler<Connection> completionHandler,
            final Throwable failure) {

        connection.closeSilently();

        if (completionHandler != null) {
            completionHandler.failed(failure);
        }
    }

    private final class ConnectHandler extends EmptyCompletionHandler<RegisterChannelResult> {

        private final UDPNIOConnection connection;
        private final CompletionHandler<Connection> completionHandler;

        private ConnectHandler(final UDPNIOConnection connection,
                final CompletionHandler<Connection> completionHandler) {
            this.connection = connection;
            this.completionHandler = completionHandler;
        }

        @Override
        public void completed(RegisterChannelResult result) {
            final UDPNIOTransport transport =
                    (UDPNIOTransport) UDPNIOConnectorHandler.this.transport;

            transport.registerChannelCompletionHandler.completed(result);

            try {
                connection.onConnect();
                
                if (connection.notifyReady()) {
                    transport.getIOStrategy().executeIOEvent(
                            connection, IOEvent.CONNECT,
                            new EnableReadHandler(completionHandler));
                }
            } catch (Exception e) {
                abortConnection(connection, completionHandler, e);
//                LOGGER.log(Level.FINE, "Exception happened, when "
//                        + "trying to connect the channel", e);
            }
        }

        @Override
        public void failed(final Throwable throwable) {
            abortConnection(connection, completionHandler, throwable);
        }
    }
    // COMPLETE, COMPLETE_LEAVE, REREGISTER, RERUN, ERROR, TERMINATE, NOT_RUN
//    private final static boolean[] isRegisterMap = {true, false, true, false, false, false, true};

    // PostProcessor, which supposed to enable OP_READ interest, once Processor will be notified
    // about Connection CONNECT
    private static class EnableReadHandler extends EventProcessingHandler.Adapter {

        private final CompletionHandler<Connection> completionHandler;

        private EnableReadHandler(
                final CompletionHandler<Connection> completionHandler) {
            this.completionHandler = completionHandler;
        }

//        @Override
//        public void onReregister(final Context context) throws IOException {
//            onComplete(context, null);
//        }

        @Override
        public void onNotRun(final Context context) throws IOException {
            onComplete(context);
        }
        
        @Override
        public void onComplete(final Context context)
                throws IOException {
            final NIOConnection connection = (NIOConnection) context.getConnection();

            if (completionHandler != null) {
                completionHandler.completed(connection);
            }
            
            connection.registerKeyInterest(SelectionKey.OP_READ);
        }

        @Override
        public void onError(final Context context, final Object description)
                throws IOException {
            context.getConnection().closeSilently();
        }
    }

    /**
     * Return the {@link UDPNIOConnectorHandler} builder.
     *
     * @param transport {@link UDPNIOTransport}.
     * @return the {@link UDPNIOConnectorHandler} builder.
     */
    public static Builder builder(final UDPNIOTransport transport) {
        return new UDPNIOConnectorHandler.Builder(transport);
    }

    public static class Builder extends AbstractSocketConnectorHandler.Builder<Builder> {
        protected Builder(final UDPNIOTransport transport) {
            super(new UDPNIOConnectorHandler(transport));
        }

        public UDPNIOConnectorHandler build() {
            return (UDPNIOConnectorHandler) connectorHandler;
        }

        public Builder setReuseAddress(final boolean isReuseAddress) {
            ((UDPNIOConnectorHandler) connectorHandler).setReuseAddress(isReuseAddress);
            return this;
        }
    }
}