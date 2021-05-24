// Web socket configuration
const host = "172.16.42.3" //window.location.host;
const port = "8080" //window.location.port;
const path = "/socket";
const ssl = false;
const protocol = ssl ? "wss" : "ws";
const webSocketUrl = protocol + "://" + host + ":" + port + path;
var webSocket = null;

// Media stream
const remoteStream = new MediaStream();
//const remoteVideo = document.querySelector('#remoteVideo');
//remoteVideo.srcObject = remoteStream;

// Image Test
const imageStream = document.querySelector('#imageStream');

function initWebSocket() {
    console.log('Initializing socket...');
    webSocket = new WebSocket(webSocketUrl);

    webSocket.onopen = async function() {
        console.log('Socket is open!');
        await sleep(2000);
    }

    webSocket.onmessage = function(e) {
        msg = e.data;
        console.log('Message received: ', msg);
        var urlCreator = window.URL || window.webkitURL;
        var imageUrl = urlCreator.createObjectURL(msg);
        imageStream.src = imageUrl;
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

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

initWebSocket();