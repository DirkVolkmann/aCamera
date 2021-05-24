// Web socket configuration
const host = "172.16.42.3" //windows.location.host;
const port = "8080" //windows.location.port;
const path = "/socket";
const ssl = false;
const protocol = ssl ? "wss" : "ws";
const webSocketUrl = protocol + "://" + host + ":" + port + path;
var webSocket = null;

// Peer connection configuration
const configuration = {'iceServers': [{'urls': 'stun:stun.l.google.com:19302'}]};
const peerConnection = window.peerConnection = new RTCPeerConnection(configuration);

// Media stream
const remoteStream = new MediaStream();
const remoteVideo = document.querySelector('#remoteVideo');
remoteVideo.srcObject = remoteStream;

var iceTmp = null;
peerConnection.addEventListener('icecandidate', event => {
    console.log("New ICE candidate:\n", event);

    iceTmp = event;
    if (event.candidate) {
        candidate = {
            "sdpMid": event.candidate.sdpMid,
            "sdpMLineIndex": event.candidate.sdpMLineIndex,
            "sdp": peerConnection.localDescription.sdp
        };

        console.log("Adding ICE candidate:\n", candidate);
        webSocket.send(JSON.stringify(candidate));
    }
});

peerConnection.addEventListener('connectionstatechange', event => {
    console.log("Connection state change event: ", event);
    console.log("New connection state: ", peerConnection.connectionState);
    if (peerConnection.connectionState === 'connected') {
        console.log("Peers connected!");
    }
});

peerConnection.addEventListener('track', async (event) => {
    console.log("Adding remote stream...");
    remoteStream.addTrack(event.track, remoteStream);
});


function initWebSocket() {
    console.log('Initializing socket...');
    webSocket = new WebSocket(webSocketUrl);

    webSocket.onopen = async function() {
        console.log('Socket is open!');
        await sleep(2000);
        createOffer();
    }

    webSocket.onmessage = function(e) {
        msg = JSON.parse(e.data);
        console.log('Message received: ', msg);
        if (msg.type.toLowerCase() == 'answer') {
            // JS is very picky about the description
            // type has to be lowercase
            msg.type = msg.type.toLowerCase();
            // description is called sdp
            if (msg.description) {
                msg.sdp = msg.description;
                delete msg.description;
            }
            setRemoteDescription(msg);
        }
    };

    webSocket.onclose = function() {
        console.log('Socket was closed, trying to reconnect...');
        initWebSocket();
    };

    webSocket.onerror = function() {
        console.error('Socket encountered error, closing socket.');
        webSocket.close();
    };
}

/*
async function connectToWebSocket() {
    while (webSocket.readyState != webSocket.OPEN) {
        switch (webSocket.readyState) {
            case webSocket.CLOSED:
                console.log("WebSocket closed, trying to reconnect...");
                webSocket.close;
                webSocket = initWebSocket(webSocketUrl);
                break;
            case webSocket.CLOSING:
                break;
            case webSocket.CONNECTING:
                console.log("WebSocket connecting...");
                break;
            case webSocket.OPEN:
                break;
        }
        await sleep(1000);
    }
    console.log("WebSocket connected!");
}
*/

async function createOffer() {    
    // Set offer options for peer connection
    const offerOptions = {
        offerToReceiveVideo: 1
    }
    // Send offer to socket
    const offer = await peerConnection.createOffer(offerOptions);
    console.log("Sending offer:\n", offer);
    webSocket.send(JSON.stringify(offer));
    setLocalDescription(offer)
}

async function setLocalDescription(offer) {
    console.log("Setting local peer description:\n" + offer.sdp);
    await peerConnection.setLocalDescription(offer);
}

async function setRemoteDescription(message) {
    console.log("Setting remote peer description:\n" + message.sdp);
    const remoteDesc = new RTCSessionDescription(message);
    await peerConnection.setRemoteDescription(remoteDesc);
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

initWebSocket();