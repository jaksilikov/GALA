import WebSocket from 'ws';
import crypto from 'crypto';

const ws = new WebSocket('wss://cs.mobstudio.ru:6672/', {
    rejectUnauthorized: false
});

let webhash = '';
let MyID = '';
let MyPass = '';
let MyNick = '';

ws.on('open', () => {
    console.log('Connected to WebSocket server');

    sendMessage(':ru IDENT 352 -2 4030 1 2 :GALA');
});

ws.on('message', async (data) => {
    const message = data.toString();

    console.log('<<', message);

    const TS = message
        .split(' ')
        .map(item => item.trim());

    // Получаем webhash
    if (TS[0] === 'HAAAPSI') {
        webhash = parse(TS[1]);

        console.log('webhash:', webhash);

        sendMessage('RECOVER 5gr2x8c18k');
    }

    // Получаем данные пользователя
    if (TS[0] === 'REGISTER') {
        MyID = TS[1] || '';
        MyPass = TS[2] || '';
        MyNick = TS[3] || '';

        console.log('REGISTER:');
        console.log('ID:', MyID);
        console.log('PASS:', MyPass);
        console.log('NICK:', MyNick);

        if (MyID && MyPass && MyNick && webhash) {
            sendMessage(
                `USER ${MyID} ${MyPass} ${MyNick} ${webhash.trim()}`
            );
        } else {
            console.error(
                'Missing required parameters for USER message'
            );
        }
    }

    // Ping
    if (TS[0] === 'PING') {
        sendMessage('PONG');
    }

    // Сервер 999
    if (TS[0] === '999') {
        sendMessage('FWLISTVER 311');
        sendMessage('ADDONS 251824 1');
        sendMessage('MYADDONS 251824 1');
        sendMessage('PHONE 1920 1080 0 2 :chrome 151.0.0.0');
sendMessage('JOIN ');
sendMessage('SLEEP ');
        console.log('WebSocket authentication completed');
    }
});

ws.on('close', () => {
    console.log('Disconnected from WebSocket server');
});

ws.on('error', (err) => {
    console.error('WebSocket error:', err);
});

ws.on('ping', () => {
    console.log('WebSocket ping received');
});

function sendMessage(text) {
    if (ws.readyState === WebSocket.OPEN) {
        ws.send(text + ' \r\n');
        console.log('>>', text);
    } else {
        console.log('WebSocket is not open. Message not sent:', text);
    }
}

function parse(e) {
    if (!e || typeof e !== 'string') {
        console.error('Invalid input for parse function');
        return null;
    }

    try {
        const hash = crypto
            .createHash('md5')
            .update(e)
            .digest('hex');

        return hash
            .split('')
            .reverse()
            .join('0')
            .substr(5, 10);

    } catch (error) {
        console.error('Hash error:', error);
        return null;
    }
}
