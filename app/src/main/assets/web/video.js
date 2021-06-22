/**
 * Web socket configuration
 */

let webSocket;
const host = window.location.hostname;
const port = window.location.port;
const path = '/socket';
const ssl = document.location.protocol == 'https:';
const protocol = ssl ? 'wss' : 'ws';
const webSocketUrl = protocol + '://' + host + ':' + port + path;

/**
 * Peer connection configuration
 */

let peerConnection;
const configuration = {};
const offerOptions = {
    offerToReceiveVideo: 1,
    offerToReceiveAudio: 1
};

/**
 * Media stream configuration
 */

let remoteStream;
const remoteVideo = document.querySelector('#remoteVideo');
remoteVideo.addEventListener('loadedmetadata', function() {
    console.log(`Remote video videoWidth: ${this.videoWidth}px,  videoHeight: ${this.videoHeight}px`);
});

/**
 * Initialize the web socket, peer connection and media stream
 */

function init() {
    // Create web socket
    console.log('Connecting to web socket:', webSocketUrl);
    webSocket = new WebSocket(webSocketUrl);
    // Add ws listeners
    webSocket.onopen = (event) => wsOnOpen(event);
    webSocket.onmessage = (event) => wsOnMessage(event);
    webSocket.onclose = (event) => wsOnClose(event);
    webSocket.onerror = (event) => wsOnError(event);

    // Create peer connection
    console.log('Creating peer connection object with configuration: ', configuration);
    peerConnection = window.peerConnection = new RTCPeerConnection(configuration);
    console.log('Completed creating peer connection object');
    // Add pc event listeners
    peerConnection.addEventListener('icecandidate', event => onLocalIceCandidateReceived(event));
    peerConnection.addEventListener('iceconnectionstatechange', event => onIceStateChanged(event));
    peerConnection.addEventListener('track', remoteStreamReceived);

    // Create media stream
    remoteStream = new MediaStream();
}

/**
 * Web socket functions
 */

function wsOnOpen(event) {
    console.log('Socket is open!');
}

function wsOnMessage(event) {
    message = JSON.parse(event.data);
    console.log('Message received: ', message);

    // Is there any content?
    if (message == null) {
        return
    }

    // Offer received?
    if (message.type?.toLowerCase() == 'offer') {
        console.log("Offer received!");
        // JS is very picky about the description
        // type has to be lowercase
        message.type = message.type.toLowerCase();
        // description is called sdp
        if (message.description) {
            message.sdp = message.description;
            delete message.description;
        }
        onOfferReceived(message);
        return;
    }

    // Remote ICE candidate received?
    if (message.sdp) {
        const regex = /^candidate:/;
        if (regex.exec(message.sdp)) {
            onRemoteIceCandidateReceived(message);
        }
        return;
    }
}

function wsOnError(error) {
    console.error('Socket encountered error: ', error);
    webSocket.close();
}

function wsOnClose(event) {
    let message = 'Socket was closed';
    if (event.reason) {
        message += ': ' + event.reason;
    }
    console.log(message);

    console.log('Closing peer connection...');
    peerConnection.close();
    peerConnection = null;
    remoteStream = null;
    console.log('Peer connection closed');

    console.log('Trying to reconnect...');
    init();
}

/**
 * Peer connection functions
 */

function onOfferReceived(offer) {
    setRemoteDescription(offer);
}

async function setRemoteDescription(offer) {
    console.log("Setting remote description...");
    try {
        const description = new RTCSessionDescription(offer);
        await peerConnection.setRemoteDescription(description);
        onSetRemoteDescriptionSuccess();
    } catch (error) {
        onSetRemoteDescriptionFailed(error);
    }
}

function onSetRemoteDescriptionSuccess() {
    console.log("Completed setting remote description");
    createAnswer();
}

function onSetRemoteDescriptionFailed(error) {
    console.log("Failed to set remote description: ", error);
}

async function createAnswer() {
    console.log("Creating answer...");
    try {
        const answer = await peerConnection.createAnswer();
        console.log("Answer created:\n", answer.sdp);
        onCreateAnswerSuccess(answer);
    } catch (error) {
        onCreateAnswerFailed(error);
    }
}

function onCreateAnswerSuccess(answer) {
    setLocalDescription(answer);
}

function onCreateAnswerFailed(error) {
    console.log("Failed to create answer: ", error);
}

async function setLocalDescription(description) {
    console.log("Setting local description...");
    try {
        await peerConnection.setLocalDescription(description);
        onSetLocalDescriptionSuccess(description);
    } catch (error) {
        onSetLocalDescriptionFailed();
    }
}

function onSetLocalDescriptionSuccess(description) {
    console.log("Completed setting local description");
    sendAnswer(description);
}

function onSetLocalDescriptionFailed() {
    console.log("Failed to set local description");
}

function sendAnswer(answer) {
    console.log("Sending answer...");
    try {
        webSocket.send(JSON.stringify(answer));
        onSendAnswerSuccess();
    } catch (error) {
        onSendAnswerFailed(error);
    }
}

function onSendAnswerSuccess() {
    console.log("Completed sending answer");
}

function onSendAnswerFailed(error) {
    console.log("Failed sending answer: ", error);
}

/**
 * ICE candidate functions
 */

function onLocalIceCandidateReceived(event) {
    console.log("New ICE candidate: ", event.candidate)
    sendIceCandidate(event.candidate);
}

function sendIceCandidate(candidate) {
    console.log("Sending ICE candidate...")
    try {
        webSocket.send(JSON.stringify(candidate));
        
    } catch (error) {
        onSendIceCandidateFailed(error);
    }
}

function onSendIceCandidateSuccess() {
    console.log("Completed sending ICE candidate");
}

function onSendIceCandidateFailed(error) {
    console.log("Failed to send ICE candidate: ", error);
}

async function onRemoteIceCandidateReceived(candidate) {
    try {
        await setRemoteIceCandidate(candidate);
        onSetRemoteIceCandidateSuccess();
    } catch (error) {
        onSetRemoteIceCandidateFailed(error);
    }
}

async function setRemoteIceCandidate(candidate) {
    console.log("Setting ICE candidate...");
    const rtcIceCandidate = new RTCIceCandidate({
        candidate: candidate.sdp,
        sdpMid: candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex
    });
    await peerConnection.addIceCandidate(rtcIceCandidate);
}

function onSetRemoteIceCandidateSuccess() {
    console.log("Completed setting ICE candidate");
}

function onSetRemoteIceCandidateFailed(error) {
    console.log("Tryed setting ICE candidate: ", candidate);
    console.log("Failed setting ICE candidate: ", error);
}

async function onIceStateChanged(event) {
    console.log("ICE state changed: ", event);
    console.log("New ICE state: ", peerConnection.iceConnectionState);
    if (peerConnection.iceConnectionState == 'failed') {
        webSocket.close();
    }
}

/**
 * Media stream functions
 */

function remoteStreamReceived(event) {
    if (remoteVideo.srcObject !== event.streams[0]) {
        remoteVideo.srcObject = event.streams[0];
        console.log('Received remote stream: ', event);
    }
}

init();