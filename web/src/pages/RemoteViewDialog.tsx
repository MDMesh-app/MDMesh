import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { getRemoteViewSession, remoteViewSocketUrl, requestRemoteView, stopRemoteViewSession, type RemoteViewSession } from '../api/remoteView';
import { Button, Callout, Modal } from '../ui';
import './RemoteViewDialog.css';

type Stage = 'waiting' | 'connecting' | 'viewing' | 'ended' | 'error';

function embeddedIceCandidateCount(sdp: string | null | undefined): number {
  return (sdp?.match(/^a=candidate:/gm) ?? []).length;
}

function stateText(session: RemoteViewSession | null, stage: Stage): string {
  if (stage === 'connecting') return 'Connecting the secure viewer…';
  if (stage === 'viewing') return 'Viewing the device screen. This view has no input control.';
  if (stage === 'ended') return session?.endReason || 'The screen-share session ended.';
  if (stage === 'error') return session?.endReason || 'Could not start the screen-share viewer.';
  if (session?.state === 'awaiting_consent') return 'Waiting for the person holding the device to approve screen sharing.';
  return 'Requesting screen-share consent on the device…';
}

/** LAN POC viewer: cookie-authenticated signaling, video-only WebRTC, and no input/data channel. */
export function RemoteViewDialog({ deviceNumber, onClose }: { deviceNumber: string; onClose: () => void }) {
  const [session, setSession] = useState<RemoteViewSession | null>(null);
  const [stage, setStage] = useState<Stage>('waiting');
  const [transportStatus, setTransportStatus] = useState('Waiting for device signaling…');
  const video = useRef<HTMLVideoElement>(null);
  const socket = useRef<WebSocket | null>(null);
  const peer = useRef<RTCPeerConnection | null>(null);
  const stopped = useRef(false);
  const sessionId = useRef<string | null>(null);

  useEffect(() => {
    let live = true;
    let poll: ReturnType<typeof setTimeout> | undefined;
    let videoStatsInterval: number | undefined;
    const finish = (next: Stage, value?: RemoteViewSession | null) => {
      if (!live) return;
      if (value) setSession(value);
      setStage(next);
    };
    const closeTransport = () => {
      if (videoStatsInterval) window.clearInterval(videoStatsInterval);
      socket.current?.close(); socket.current = null;
      peer.current?.close(); peer.current = null;
    };
    const stop = () => {
      if (stopped.current) return;
      stopped.current = true;
      closeTransport();
      if (sessionId.current) void stopRemoteViewSession(sessionId.current).catch(() => undefined);
    };
    const connect = (current: RemoteViewSession) => {
      if (!live || stopped.current) return;
      setStage('connecting');
      // STUN discovers candidate addresses only. Media remains direct between
      // the viewer and device; this POC deliberately does not configure TURN.
      const pc = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
        rtcpMuxPolicy: 'require',
        iceTransportPolicy: 'all',
      });
      peer.current = pc;
      let localCandidateCount = 0;
      let agentCandidateCount = 0;
      let answerSent = false;
      // A receive-only transceiver makes the browser offer a video media section.
      // offerToReceiveVideo alone can produce an empty offer in modern browsers;
      // Android WebRTC cannot negotiate the screen sender against that offer.
      pc.addTransceiver('video', { direction: 'recvonly' });
      const pendingCandidates: RTCIceCandidateInit[] = [];
      let remoteDescriptionInstalled = false;
      const videoRenderDiagnostic = () => {
        const element = video.current;
        if (!element || element.videoWidth === 0 || element.videoHeight === 0) return 'video metadata unavailable';
        const canvas = document.createElement('canvas');
        canvas.width = 3;
        canvas.height = 3;
        const context = canvas.getContext('2d', { willReadFrequently: true });
        if (!context) return `${element.videoWidth}x${element.videoHeight}, playback ${element.currentTime.toFixed(1)}s`;
        context.drawImage(element, 0, 0, canvas.width, canvas.height);
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let brightness = 0;
        for (let index = 0; index < pixels.length; index += 4) brightness += pixels[index] + pixels[index + 1] + pixels[index + 2];
        const average = Math.round(brightness / (pixels.length / 4) / 3);
        return `${element.videoWidth}x${element.videoHeight}, playback ${element.currentTime.toFixed(1)}s, sampled brightness ${average}/255`;
      };
      const updateVideoStats = async () => {
        if (!live || pc.connectionState !== 'connected') return;
        const report = await pc.getStats();
        const inbound = [...report.values()].find((item) => {
          const video = item as RTCInboundRtpStreamStats;
          return item.type === 'inbound-rtp' && video.kind === 'video';
        }) as RTCInboundRtpStreamStats | undefined;
        if (!inbound) {
          setTransportStatus('Viewer has no inbound video RTP statistics yet.');
          return;
        }
        setTransportStatus(
          `Viewer video: bytes ${inbound.bytesReceived}, decoded ${inbound.framesDecoded ?? 0}, dropped ${inbound.framesDropped ?? 0}; ${videoRenderDiagnostic()}`,
        );
      };
      pc.ontrack = (event) => {
        const stream = event.streams[0];
        if (stream && video.current) {
          video.current.srcObject = stream;
          video.current.muted = true;
          void video.current.play().catch((error: unknown) => {
            setTransportStatus(`Remote video play was blocked: ${error instanceof Error ? error.name : 'unknown error'}`);
          });
          setStage('viewing');
          setTransportStatus('Remote video track received; checking decoded frames…');
          void updateVideoStats();
          videoStatsInterval = window.setInterval(() => { void updateVideoStats(); }, 2000);
        }
      };
      pc.onicecandidate = (event) => {
        if (event.candidate) {
          localCandidateCount += 1;
          setTransportStatus(`ICE candidates: viewer ${localCandidateCount}, device ${agentCandidateCount}`);
        }
      };
      pc.onicegatheringstatechange = () => {
        if (pc.iceGatheringState === 'complete') {
          sendCompleteAnswer();
        }
      };
      pc.onicecandidateerror = (event) => {
        setTransportStatus(`ICE gathering error ${event.errorCode}: ${event.errorText || 'unknown error'}`);
      };
      pc.oniceconnectionstatechange = () => {
        setTransportStatus(`ICE ${pc.iceConnectionState}: viewer ${localCandidateCount}, device ${agentCandidateCount}`);
      };
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') finish('ended');
      };
      const sendCompleteAnswer = () => {
        if (answerSent || !pc.localDescription?.sdp) return;
        const embeddedCandidates = embeddedIceCandidateCount(pc.localDescription.sdp);
        if (embeddedCandidates === 0) {
          setTransportStatus('Viewer produced no local ICE candidates.');
          finish('error');
          stop();
          return;
        }
        answerSent = true;
        ws.send(JSON.stringify({ type: 'answer', payload: { sdp: pc.localDescription.sdp } }));
        setTransportStatus(`Viewer answer sent with ${embeddedCandidates} ICE candidate(s).`);
      };
      const ws = new WebSocket(remoteViewSocketUrl(current.id));
      socket.current = ws;
      ws.onopen = () => {
        setTransportStatus('Viewer signaling connected; waiting for device offer…');
        // The Android agent owns the screen sender and initiates negotiation.
        // Waiting for its offer avoids an Android-9 native crash while it
        // applies a browser-originated offer.
      };
      ws.onmessage = async (event) => {
        try {
          const message = JSON.parse(String(event.data)) as { type?: string; payload?: RTCSessionDescriptionInit & RTCIceCandidateInit };
          if (message.type === 'offer' && message.payload?.sdp) {
            setTransportStatus('Device offer received; creating viewer answer…');
            await pc.setRemoteDescription({ type: 'offer', sdp: message.payload.sdp });
            remoteDescriptionInstalled = true;
            while (pendingCandidates.length > 0) {
              const candidate = pendingCandidates.shift();
              if (candidate) await pc.addIceCandidate(candidate);
            }
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            setTransportStatus('Viewer answer set; gathering local ICE candidates…');
            window.setTimeout(sendCompleteAnswer, 5000);
          }
          if (message.type === 'candidate' && message.payload?.candidate) {
            agentCandidateCount += 1;
            setTransportStatus(`ICE candidates: viewer ${localCandidateCount}, device ${agentCandidateCount}`);
            if (remoteDescriptionInstalled) await pc.addIceCandidate(message.payload);
            else pendingCandidates.push(message.payload);
          }
        } catch { finish('error'); stop(); }
      };
      ws.onerror = () => finish('error');
      ws.onclose = () => { if (!stopped.current) finish('ended'); };
    };
    const check = async (id: string) => {
      try {
        const current = await getRemoteViewSession(id);
        if (!live || stopped.current) return;
        setSession(current);
        if (current.state === 'signaling') { connect(current); return; }
        if (current.endedAt || ['stopped', 'denied', 'failed', 'disconnected'].includes(current.state)) { finish('ended', current); return; }
        poll = setTimeout(() => void check(id), 1000);
      } catch (error) { finish(error instanceof ApiError && error.httpStatus === 403 ? 'ended' : 'error'); }
    };
    void requestRemoteView(deviceNumber).then((created) => {
      sessionId.current = created.id;
      setSession(created);
      if (created.state === 'signaling') connect(created);
      else void check(created.id);
    }).catch(() => finish('error'));
    return () => { live = false; clearTimeout(poll); stop(); };
  // Start once for the opened dialog; deviceNumber is stable for its lifetime.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const close = () => onClose();
  return <Modal open onClose={close} title="View device screen" description="The device must approve this short, view-only session." size="full" dismissOnBackdrop={false}
    footer={<Button variant="subtle" size="lg" onClick={close}>End view</Button>}>
    <div className="v2-remote-view">
      <p className="v2-remote-view__state" role="status">{stateText(session, stage)}</p>
      <p className="v2-remote-view__transport" role="status">{transportStatus}</p>
      {stage === 'error' ? <Callout tone="alert">The viewer could not connect. No screen content was stored.</Callout> : null}
      <video ref={video} className="v2-remote-view__video" autoPlay playsInline aria-label="Live device screen" />
    </div>
  </Modal>;
}
