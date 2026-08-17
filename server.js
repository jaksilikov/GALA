import WebSocket from 'ws';
import crypto from 'crypto';

const WS_URL = 'wss://cs.mobstudio.ru:6672/';
const RECONNECT_INTERVAL = 20 * 60 * 1000; // 20 минут

let ws = null;

let webhash = '';
let MyID = '';
let MyPass = '';
let MyNick = '';

let reconnectTimer = null;

function connect() {
    console.log('Connecting to WebSocket...');

    webhash = '';
    MyID = '';
    MyPass = '';
    MyNick = '';

    ws = new WebSocket(WS_URL, {
        rejectUnauthorized: false
    });

    ws.on('open', () => {
        console.log('Connected to WebSocket server');

        sendMessage(':ru IDENT 352 -2 4030 1 2 :GALA');

        // Через 20 минут закрываем это соединение
        clearTimeout(reconnectTimer);

        reconnectTimer = setTimeout(() => {
            console.log('20 minutes passed. Reconnecting...');

            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.close();
            } else {
                connect();
            }
        }, RECONNECT_INTERVAL);
    });

    ws.on('message', async (data) => {
        const message = data.toString();

        console.log('<<', message);

        const TS = message
            .split(' ')
            .map(item => item.trim());

        // HAAAPSI
        if (TS[0] === 'HAAAPSI') {
            webhash = parse(TS[1]);

            console.log('webhash:', webhash);

            sendMessage('RECOVER 5gr2x8c18k');
        }

        // REGISTER
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

        // PING
        if (TS[0] === 'PING') {
            sendMessage('PONG');
        }

        // 999
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

    ws.on('close', (code, reason) => {
        console.log(
            'Disconnected from WebSocket server',
            'code:', code,
            'reason:', reason.toString()
        );

        // Если сервер сам отключил соединение,
        // переподключаемся через 5 секунд.
        clearTimeout(reconnectTimer);

        reconnectTimer = setTimeout(() => {
            console.log('Reconnecting after server disconnect...');
            connect();
        }, 5000);
    });

    ws.on('error', (err) => {
        console.error('WebSocket error:', err.message);
    });

    ws.on('ping', () => {
        console.log('WebSocket ping received');
    });
}

function sendMessage(text) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(text + ' \r\n');
        console.log('>>', text);
    } else {
        console.log(
            'WebSocket is not open. Message not sent:',
            text
        );
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

// Первое подключение
connect();
