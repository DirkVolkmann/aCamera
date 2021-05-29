/**
 * Web socket configuration
 */

let webSocket;
const host = "172.16.42.3" //windows.location.host;
const port = "8080" //windows.location.port;
const path = "/socket";
const ssl = false;
const protocol = ssl ? "wss" : "ws";
const webSocketUrl = protocol + "://" + host + ":" + port + path;

/**
 * Peer connection configuration
 */

const configuration = {'iceServers': [/*{'urls': 'stun:stun.l.google.com:19302'}*/]};
const offerOptions = { offerToReceiveVideo: 1 };
console.log('RTCPeerConnection configuration:', configuration);
const peerConnection = window.peerConnection = new RTCPeerConnection(configuration);
console.log('Created remote peer connection object');
peerConnection.addEventListener('icecandidate', e => onIceCandidate(e));
peerConnection.addEventListener('iceconnectionstatechange', e => onIceStateChange(e));
peerConnection.addEventListener('track', remoteStreamReceived);

/**
 * Media stream configuration
 */

const remoteStream = new MediaStream();
const remoteVideo = document.querySelector('#remoteVideo');
remoteVideo.addEventListener('loadedmetadata', function() {
    console.log(`Remote video videoWidth: ${this.videoWidth}px,  videoHeight: ${this.videoHeight}px`);
});

/**
 * Web socket functions
 */

function initWebSocket() {
    console.log('Initializing socket...');
    webSocket = new WebSocket(webSocketUrl);

    webSocket.onopen = async function() {
        console.log('Socket is open!');
    }

    webSocket.onmessage = function(e) {
        msg = JSON.parse(e.data);
        console.log('Message received: ', msg);

        // Offer?
        if (msg.type?.toLowerCase() == 'offer') {
            console.log("Message was offer");
            // JS is very picky about the description
            // type has to be lowercase
            msg.type = msg.type.toLowerCase();
            // description is called sdp
            if (msg.description) {
                msg.sdp = msg.description;
                delete msg.description;
            }
            onReceivedOffer(msg);
        }

        // ICE?
        else if (msg.sdp) {
            const regex = /^candidate:/;
            if (regex.exec(msg.sdp)) {
                onReceivedIce(msg.sdp);
            }
        }
    };

    webSocket.onclose = function() {
        console.log('Socket was closed');
        initWebSocket();
    };

    webSocket.onerror = function() {
        console.error('Socket encountered error, closing socket.');
        webSocket.close();
    };
}

/**
 * Offer received and send answer
 */

async function onReceivedOffer(offer) {
    console.log("Setting remote description...");
    try {
        description = new RTCSessionDescription(offer)
        await peerConnection.setRemoteDescription(description);
        console.log("Setting remote description completed");
    } catch (error) {
        console.log("Failed to set remote description: ", error);
    }

    console.log("Creating answer...");
    try {
        const answer = await peerConnection.createAnswer();
        console.log("Answer created: ", answer.sdp);
        await onCreateAnswerSuccess(answer);
    } catch (error) {
        console.log("Failed to create answer: ", error);
    }
}

async function onCreateAnswerSuccess(message) {
    console.log("Setting local description...");
    try {
        await peerConnection.setLocalDescription(message);
        console.log("Setting local description completed");
    } catch (error) {
        console.log("Failed to set local description");
    }

    console.log("Sending answer...");
    try {
        webSocket.send(JSON.stringify(message));
        console.log("Sending answer completed");
    } catch (error) {
        console.log("Sending anser failed: ", error);
    }
}

/**
 * ICE candidate functions
 */

async function onIceCandidate(event) {
    console.log("ICE candidate: ", event.candidate)
    console.log("Sending ICE candidate...")
    try {
        webSocket.send(JSON.stringify(event.candidate));
        console.log("Sending ICE candidate completed")
    } catch (error) {
        console.log("Sending ICE candidate failed: ", error);
    }
}

async function onIceStateChange(event) {
    console.log("ICE state changed: ", event);
    console.log("New ICE state: ", peerConnection.iceConnectionState);
}

async function onReceivedIce(candidate) {
    try {
        console.log("Setting ICE candidate...")
        const rtcIceCandidate = new RTCIceCandidate(candidate);
        await peerConnection.addIceCandidate(rtcIceCandidate)
        console.log("Setting ICE candidate completed")
    } catch (error) {
        console.log("Setting ICE candidate failed: ", error);
    }
}

/**
 * Other
 */

function remoteStreamReceived(event) {
    if (remoteVideo.srcObject !== event.streams[0]) {
        remoteVideo.srcObject = event.streams[0];
        console.log('Received remote stream');
    }
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

initWebSocket();