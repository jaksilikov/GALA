import express from 'express';
import WebSocket from 'ws';
import crypto from 'crypto';

const app = express();

const PORT = process.env.PORT || 10000;
const WS_URL = 'wss://cs.mobstudio.ru:6672/';

const RECONNECT_INTERVAL = 14 * 60 * 1000;

let ws = null;
let reconnectTimer = null;
let connectionId = 0;

let webhash = '';
let MyID = '';
let MyPass = '';
let MyNick = '';

app.get('/', (req, res) => {
    res.json({
        ok: true,
        service: 'GalaxyBot',
        websocket: ws
            ? ws.readyState === WebSocket.OPEN
                ? 'connected'
                : 'disconnected'
            : 'not_started',
        next_reconnect: '20 minutes'
    });
});

app.get('/health', (req, res) => {
    res.json({
        ok: true,
        websocket_connected:
            ws?.readyState === WebSocket.OPEN
    });
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`HTTP server started on port ${PORT}`);
    connect();
    startReconnectTimer();
});

function connect() {

    connectionId++;

    const id = connectionId;

    console.log('');
    console.log('================================');
    console.log(`WebSocket connection #${id}`);
    console.log('Connecting...');
    console.log('================================');

    webhash = '';
    MyID = '';
    MyPass = '';
    MyNick = '';

    const socket = new WebSocket(WS_URL, {
        rejectUnauthorized: false
    });

    ws = socket;

    socket.on('open', () => {

        console.log(`[${id}] Connected`);

        sendMessage(
            socket,
            ':ru IDENT 352 -2 4030 1 2 :GALA'
        );
    });

    socket.on('message', (data) => {

        const message = data.toString();

        console.log('<<', message);

        const TS = message
            .split(' ')
            .map(item => item.trim());

        /*
         * HAAAPSI
         */

        if (TS[0] === 'HAAAPSI') {

            webhash = parse(TS[1]);

            console.log(
                'webhash:',
                webhash
            );

            if (webhash) {

                sendMessage(
                    socket,
                    'RECOVER 5gr2x8c18k'
                );
            }
        }

        /*
         * REGISTER
         */

        if (TS[0] === 'REGISTER') {

            MyID = TS[1] || '';
            MyPass = TS[2] || '';
            MyNick = TS[3] || '';

            console.log('REGISTER:');
            console.log('ID:', MyID);
            console.log('PASS:', MyPass);
            console.log('NICK:', MyNick);

            if (
                MyID &&
                MyPass &&
                MyNick &&
                webhash
            ) {

                sendMessage(
                    socket,
                    `USER ${MyID} ${MyPass} ${MyNick} ${webhash.trim()}`
                );

            } else {

                console.error(
                    'Missing required parameters'
                );
            }
        }

        /*
         * PING
         */

        if (TS[0] === 'PING') {

            sendMessage(
                socket,
                'PONG'
            );
        }

        /*
         * 999
         */

        if (TS[0] === '999') {

            sendMessage(
                socket,
                'FWLISTVER 311'
            );

            sendMessage(
                socket,
                'ADDONS 251824 1'
            );

            sendMessage(
                socket,
                'MYADDONS 251824 1'
            );

            sendMessage(
                socket,
                'PHONE 1920 1080 0 2 :chrome 151.0.0.0'
            );

            sendMessage(
                socket,
                'JOIN '
            );

            console.log(
                'JOIN sent'
            );

            /*
             * 2 секунды после JOIN
             */

            setTimeout(() => {

                if (
                    socket.readyState !==
                    WebSocket.OPEN
                ) {
                    return;
                }

                sendMessage(
                    socket,
                    'REMOVE 96'
                );

                console.log(
                    'REMOVE 96 sent'
                );

                /*
                 * Ещё 3 секунды
                 */

                setTimeout(() => {

                    if (
                        socket.readyState !==
                        WebSocket.OPEN
                    ) {
                        return;
                    }

                    sendMessage(
                        socket,
                        'OBJ_ACT 5 15170420 1 go_to_bed'
                    );

                    console.log(
                        'OBJ_ACT sent'
                    );

                    sendMessage(
                        socket,
                        'SLEEP '
                    );

                }, 3000);

            }, 2000);

            console.log(
                'WebSocket authentication completed'
            );
        }
    });

    socket.on('close', (code, reason) => {

        console.log('');
        console.log(
            `[${id}] WebSocket disconnected`
        );

        console.log(
            'Code:',
            code
        );

        console.log(
            'Reason:',
            reason.toString()
        );

        /*
         * Никакого reconnect здесь нет.
         *
         * Переподключение выполняется
         * только общим 20-минутным таймером.
         */
    });

    socket.on('error', (err) => {

        console.error(
            `[${id}] WebSocket error:`,
            err.message
        );
    });

    socket.on('ping', () => {

        console.log(
            `[${id}] WebSocket ping`
        );
    });
}

function startReconnectTimer() {

    clearTimeout(reconnectTimer);

    reconnectTimer = setTimeout(() => {

        console.log('');
        console.log(
            '================================'
        );

        console.log(
            '20 MINUTES PASSED'
        );

        console.log(
            'Reconnecting WebSocket...'
        );

        console.log(
            '================================'
        );

        /*
         * Закрываем старое соединение
         */

        if (
            ws &&
            ws.readyState === WebSocket.OPEN
        ) {

            ws.close();

        } else if (
            ws &&
            ws.readyState === WebSocket.CONNECTING
        ) {

            ws.terminate();
        }

        /*
         * Создаём новое
         */

        connect();

        /*
         * Снова ставим таймер
         */

        startReconnectTimer();

    }, RECONNECT_INTERVAL);
}

function sendMessage(socket, text) {

    if (
        socket &&
        socket.readyState === WebSocket.OPEN
    ) {

        socket.send(
            text + ' \r\n'
        );

        console.log(
            '>>',
            text
        );

    } else {

        console.log(
            'WebSocket is not open:',
            text
        );
    }
}

function parse(e) {

    if (
        !e ||
        typeof e !== 'string'
    ) {

        console.error(
            'Invalid input for parse function'
        );

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

        console.error(
            'Hash error:',
            error
        );

        return null;
    }
}
