/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package org.kurento.tutorial.crowddetector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.module.crowddetector.CrowdDetectorDirectionEvent;
import org.kurento.module.crowddetector.CrowdDetectorFilter;
import org.kurento.module.crowddetector.CrowdDetectorFluidityEvent;
import org.kurento.module.crowddetector.CrowdDetectorOccupancyEvent;
import org.kurento.module.crowddetector.RegionOfInterest;
import org.kurento.module.crowddetector.RegionOfInterestConfig;
import org.kurento.module.crowddetector.RelativePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Magic Mirror handler (application and media logic).
 * 
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author David Fernandez (d.fernandezlop@gmail.com)
 * @since 5.0.0
 */
public class CrowdDetectorHandler extends TextWebSocketHandler {

	private final Logger log = LoggerFactory
			.getLogger(CrowdDetectorHandler.class);
	private static final Gson gson = new GsonBuilder().create();

	private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<String, UserSession>();

	@Autowired
	private KurentoClient kurento;

	@Override
	public void handleTextMessage(WebSocketSession session, TextMessage message)
			throws Exception {
		JsonObject jsonMessage = gson.fromJson(message.getPayload(),
				JsonObject.class);

		log.debug("Incoming message: {}", jsonMessage);

		switch (jsonMessage.get("id").getAsString()) {
		case "start":
			start(session, jsonMessage);
			break;

		case "stop": {
			UserSession user = users.remove(session.getId());
			if (user != null) {
				user.release();
			}
			break;
		}

		case "onIceCandidate": {
			JsonObject candidate = jsonMessage.get("candidate")
					.getAsJsonObject();

			UserSession user = users.get(session.getId());
			if (user != null) {
				IceCandidate cand = new IceCandidate(candidate.get("candidate")
						.getAsString(), candidate.get("sdpMid").getAsString(),
						candidate.get("sdpMLineIndex").getAsInt());
				user.addCandidate(cand);
			}
			break;
		}

		default:
			sendError(session,
					"Invalid message with id "
							+ jsonMessage.get("id").getAsString());
			break;
		}
	}

	private void start(final WebSocketSession session, JsonObject jsonMessage) {
		try {
			// Media Logic (Media Pipeline and Elements)
			UserSession user = new UserSession();
			MediaPipeline pipeline = kurento.createMediaPipeline();
			user.setMediaPipeline(pipeline);
			WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline)
					.build();
			user.setWebRtcEndpoint(webRtcEndpoint);
			users.put(session.getId(), user);

			webRtcEndpoint
					.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {

						@Override
						public void onEvent(OnIceCandidateEvent event) {
							JsonObject response = new JsonObject();
							response.addProperty("id", "iceCandidate");
							response.add("candidate", JsonUtils
									.toJsonObject(event.getCandidate()));
							try {
								synchronized (session) {
									session.sendMessage(new TextMessage(
											response.toString()));
								}
							} catch (IOException e) {
								log.debug(e.getMessage());
							}
						}
					});

			List<RegionOfInterest> rois = new ArrayList<>();
			List<RelativePoint> points = new ArrayList<RelativePoint>();

			points.add(new RelativePoint(0, 0));
			points.add(new RelativePoint(0.5F, 0));
			points.add(new RelativePoint(0.5F, 0.5F));
			points.add(new RelativePoint(0, 0.5F));

			RegionOfInterestConfig config = new RegionOfInterestConfig();

			config.setFluidityLevelMin(10);
			config.setFluidityLevelMed(35);
			config.setFluidityLevelMax(65);
			config.setFluidityNumFramesToEvent(5);
			config.setOccupancyLevelMin(10);
			config.setOccupancyLevelMed(35);
			config.setOccupancyLevelMax(65);
			config.setOccupancyNumFramesToEvent(5);
			config.setSendOpticalFlowEvent(false);
			config.setOpticalFlowNumFramesToEvent(3);
			config.setOpticalFlowNumFramesToReset(3);
			config.setOpticalFlowAngleOffset(0);

			rois.add(new RegionOfInterest(points, config, "roi0"));

			CrowdDetectorFilter crowdDetectorFilter = new CrowdDetectorFilter.Builder(
					pipeline, rois).build();

			webRtcEndpoint.connect(crowdDetectorFilter);
			crowdDetectorFilter.connect(webRtcEndpoint);

			// addEventListener to crowddetector
			crowdDetectorFilter
					.addCrowdDetectorDirectionListener(new EventListener<CrowdDetectorDirectionEvent>() {
						@Override
						public void onEvent(CrowdDetectorDirectionEvent event) {
							JsonObject response = new JsonObject();
							response.addProperty("id", "directionEvent");
							response.addProperty("roiId", event.getRoiID());
							response.addProperty("angle",
									event.getDirectionAngle());
							try {
								session.sendMessage(new TextMessage(response
										.toString()));
							} catch (Throwable t) {
								sendError(session, t.getMessage());
							}
						}
					});

			crowdDetectorFilter
					.addCrowdDetectorFluidityListener(new EventListener<CrowdDetectorFluidityEvent>() {
						@Override
						public void onEvent(CrowdDetectorFluidityEvent event) {
							JsonObject response = new JsonObject();
							response.addProperty("id", "fluidityEvent");
							response.addProperty("roiId", event.getRoiID());
							response.addProperty("level",
									event.getFluidityLevel());
							response.addProperty("percentage",
									event.getFluidityPercentage());
							try {
								session.sendMessage(new TextMessage(response
										.toString()));
							} catch (Throwable t) {
								sendError(session, t.getMessage());
							}
						}
					});

			crowdDetectorFilter
					.addCrowdDetectorOccupancyListener(new EventListener<CrowdDetectorOccupancyEvent>() {
						@Override
						public void onEvent(CrowdDetectorOccupancyEvent event) {
							JsonObject response = new JsonObject();
							response.addProperty("id", "occupancyEvent");
							response.addProperty("roiId", event.getRoiID());
							response.addProperty("level",
									event.getOccupancyLevel());
							response.addProperty("percentage",
									event.getOccupancyPercentage());
							try {
								session.sendMessage(new TextMessage(response
										.toString()));
							} catch (Throwable t) {
								sendError(session, t.getMessage());
							}
						}
					});

			// SDP negotiation (offer and answer)
			String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
			String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

			// Sending response back to client
			JsonObject response = new JsonObject();
			response.addProperty("id", "startResponse");
			response.addProperty("sdpAnswer", sdpAnswer);

			synchronized (session) {
				session.sendMessage(new TextMessage(response.toString()));
			}

			webRtcEndpoint.gatherCandidates();

		} catch (Throwable t) {
			sendError(session, t.getMessage());
		}
	}

	private void sendError(WebSocketSession session, String message) {
		try {
			JsonObject response = new JsonObject();
			response.addProperty("id", "error");
			response.addProperty("message", message);
			session.sendMessage(new TextMessage(response.toString()));
		} catch (IOException e) {
			log.error("Exception sending message", e);
		}
	}
}
