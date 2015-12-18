/*
 ** Copyright (C) 2013 Mellanox Technologies
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at:
 **
 ** http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 ** either express or implied. See the License for the specific language
 ** governing permissions and  limitations under the License.
 **
 */
package org.accelio.jxio.helloworld;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;

import org.accelio.jxio.Msg;
import org.accelio.jxio.MsgPool;
import org.accelio.jxio.EventName;
import org.accelio.jxio.EventReason;
import org.accelio.jxio.ClientSession;
import org.accelio.jxio.EventQueueHandler;

public class HelloClient {

	private final static Log LOG = LogFactory.getLog(HelloClient.class.getCanonicalName());
	private final MsgPool mp;
	private final EventQueueHandler eqh;
	private ClientSession client;
	public int exitStatus = 1;

	public static void main(String[] args) {
		if (args.length < 3) {
			usage();
			return;
		}

		final String transport = args[0];
		final String serverhostname = args[1];
		final int port = Integer.parseInt(args[2]);

		URI uri = null;
		try {
			uri = new URI(transport + "://" + serverhostname + ":" + port + "/");
		} catch (URISyntaxException e) {
			e.printStackTrace();
			return;
		}

		final HelloClient client = new HelloClient();
		client.connect(uri);

		/** Queue up some requests */
		client.addToQueue("Hello, Mike"); // ~ fexists
		client.addToQueue("Hello, Bob");  // ~ create

		/** Spawn thread 2 to run the Event Queue Handler */
		Runnable thread2 = new Runnable() {
			public void run() {
				LOG.info("[Thread 2] Starting EQH...");
				client.eqh.run();
			}
		};
		(new Thread(thread2)).start();
		
		/** Thread 1 continually adds messages to the Event Queue Handler */
		while(true) {

			// Uncomment this and one message gets in the EQH
			//LOG.info("[Thread 1] Adding new message...");
			//client.addToQueue("Hello, Sam"); // ~ write

			try {
				LOG.info("[Thread 1] Sleeping for 1 second...");
				Thread.sleep(1000); 
			} catch(InterruptedException ex) { Thread.currentThread().interrupt();
				Thread.currentThread().interrupt();
			}		

			// Uncomment this and the message never gets in the EQH
			LOG.info("[Thread 1] Adding new message...");
			client.addToQueue("Hello, Sam"); // ~ write
		}
		
		/** Cleanup */
		//LOG.info("Client is releasing JXIO resources and exiting");
		//client.releaseResources();
		//System.exit(client.exitStatus);
	}

	HelloClient() {
		this.eqh = new EventQueueHandler(null);
		this.mp = new MsgPool(256, 100, 100);
	}

	public void connect(URI uri) {
		LOG.info("Try to establish a new session to '" + uri + "'");
		this.client = new ClientSession(eqh, uri, new MyClientCallbacks(this));
	}
	public void addToQueue(String m) {
		Msg msg = this.mp.getMsg();
		try {
			msg.getOut().put(m.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			// Just suppress the exception handling in this demo code
		}
		try {
			this.client.sendRequest(msg);
		} catch (IOException e) {
			//all exceptions thrown extend IOException
			LOG.error(e.toString());
		}
	}

	public void run() {
		// block for JXIO incoming event
		eqh.run();
	}

	public void releaseResources() {
		mp.deleteMsgPool();
		eqh.close();
	}

	public static void usage() {
		LOG.info("Usage: ./runHelloServer.sh <tcp|rdma> <SERVER_IPADDR> [<PORT>]");
	}

	class MyClientCallbacks implements ClientSession.Callbacks {
		private final HelloClient client;

		MyClientCallbacks(HelloClient client) {
			this.client = client;
		}

		public void onSessionEstablished() {
			LOG.info("[SUCCESS] Session established! Hurray !");
		}

		public void onResponse(Msg msg) {
			LOG.info("[SUCCESS] Got a message! Bring the champagne!");

			// Read reply message String
			byte ch;
			StringBuffer buffer = new StringBuffer();
			while (msg.getIn().hasRemaining() && ((ch = msg.getIn().get()) > -1)) {
				buffer.append((char) ch);
			}
			LOG.info("msg is: '" + buffer.toString() + "'");

			msg.returnToParentPool();

			//LOG.info("Closing the session...");
			//this.client.client.close();

			exitStatus = 0; // Success, we got our message response back
		}

		public void onSessionEvent(EventName event, EventReason reason) {
			String str = "[EVENT] Got event " + event + " because of " + reason;
			if (event == EventName.SESSION_CLOSED) { // normal exit
				LOG.info(str);
			} else {
				this.client.exitStatus = 1; // Failure on any kind of error
				LOG.error(str);
			}
			//this.client.eqh.stop();
		}

		public void onMsgError(Msg msg, EventReason reason) {
			LOG.info("[ERROR] onMsgErrorCallback. reason=" + reason);
			if (reason == EventReason.MSG_FLUSHED) {
				LOG.info("[STATUS] getIsClosing() = " + this.client.client.getIsClosing());
			}
			msg.returnToParentPool();
			this.client.exitStatus = 1; // Failure on any kind of error
			System.exit(exitStatus);
		}
	}
}
