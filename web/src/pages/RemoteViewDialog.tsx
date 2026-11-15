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
      const pc = new RTCPeerConnection({ iceServers: [] });
      peer.current = pc;
      pc.ontrack = (event) => {
        const stream = event.streams[0];
        if (stream && video.current) {
          video.current.srcObject = stream;
          void video.current.play().catch(() => undefined);
          setStage('viewing');
        }
      };
      pc.onicecandidate = (event) => {
        if (event.candidate && socket.current?.readyState === WebSocket.OPEN) {
          socket.current.send(JSON.stringify({ type: 'candidate', payload: event.candidate.toJSON() }));
        }
      };
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') finish('ended');
      };
      const ws = new WebSocket(remoteViewSocketUrl(current.id));
      socket.current = ws;
      ws.onopen = async () => {
        try {
          const offer = await pc.createOffer({ offerToReceiveVideo: true });
          await pc.setLocalDescription(offer);
          ws.send(JSON.stringify({ type: 'offer', payload: { sdp: offer.sdp } }));
        } catch {
          finish('error');
          stop();
        }
      };
      ws.onmessage = async (event) => {
        try {
          const message = JSON.parse(String(event.data)) as { type?: string; payload?: RTCSessionDescriptionInit & RTCIceCandidateInit };
          if (message.type === 'answer' && message.payload?.sdp) await pc.setRemoteDescription({ type: 'answer', sdp: message.payload.sdp });
          if (message.type === 'candidate' && message.payload?.candidate) await pc.addIceCandidate(message.payload);
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
      {stage === 'error' ? <Callout tone="alert">The viewer could not connect. No screen content was stored.</Callout> : null}
      <video ref={video} className="v2-remote-view__video" autoPlay playsInline aria-label="Live device screen" />
    </div>
  </Modal>;
}
