import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { getRemoteViewSession, remoteViewSocketUrl, requestRemoteView, stopRemoteViewSession, type RemoteViewSession } from '../api/remoteView';
import { Button, Callout, Modal } from '../ui';
import './RemoteViewDialog.css';

type Stage = 'waiting' | 'connecting' | 'viewing' | 'ended' | 'error';

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
    const finish = (next: Stage, value?: RemoteViewSession | null) => {
      if (!live) return;
      if (value) setSession(value);
      setStage(next);
    };
    const closeTransport = () => {
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
      const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
      peer.current = pc;
      let localCandidateCount = 0;
      let agentCandidateCount = 0;
      // A receive-only transceiver makes the browser offer a video media section.
      // offerToReceiveVideo alone can produce an empty offer in modern browsers;
      // Android WebRTC cannot negotiate the screen sender against that offer.
      pc.addTransceiver('video', { direction: 'recvonly' });
      const pendingCandidates: RTCIceCandidateInit[] = [];
      let remoteDescriptionInstalled = false;
      pc.ontrack = (event) => {
        const stream = event.streams[0];
        if (stream && video.current) {
          video.current.srcObject = stream;
          void video.current.play().catch(() => undefined);
          setStage('viewing');
          setTransportStatus('Media is flowing.');
        }
      };
      pc.onicecandidate = (event) => {
        if (event.candidate && socket.current?.readyState === WebSocket.OPEN) {
          localCandidateCount += 1;
          setTransportStatus(`ICE candidates: viewer ${localCandidateCount}, device ${agentCandidateCount}`);
          socket.current.send(JSON.stringify({ type: 'candidate', payload: event.candidate.toJSON() }));
        }
      };
      pc.onicegatheringstatechange = () => {
        if (pc.iceGatheringState === 'complete') {
          setTransportStatus(`ICE gathering complete: viewer ${localCandidateCount}, device ${agentCandidateCount}`);
        }
      };
      pc.oniceconnectionstatechange = () => {
        setTransportStatus(`ICE ${pc.iceConnectionState}: viewer ${localCandidateCount}, device ${agentCandidateCount}`);
      };
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') finish('ended');
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
            ws.send(JSON.stringify({ type: 'answer', payload: { sdp: answer.sdp } }));
            setTransportStatus('Viewer answer sent; gathering ICE candidates…');
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
