import express from 'express';
import WebSocket from 'ws';
import crypto from 'crypto';

const app = express();

const PORT = process.env.PORT || 10000;
const WS_URL = 'wss://cs.mobstudio.ru:6672/';

// Переподключение после отключения
const DISCONNECT_RECONNECT_DELAY = 5 * 1000;

let ws = null;
let disconnectReconnectTimer = null;
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
        reconnect_after_disconnect: '5 seconds'
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

    console.log(
        `HTTP server started on port ${PORT}`
    );

    connect();
});


function connect() {

    /*
     * Если соединение уже существует,
     * второй раз не подключаемся
     */

    if (
        ws &&
        (
            ws.readyState === WebSocket.OPEN ||
            ws.readyState === WebSocket.CONNECTING
        )
    ) {

        console.log(
            'WebSocket already connected or connecting'
        );

        return;
    }

    connectionId++;

    const id = connectionId;

    console.log('');
    console.log('================================');
    console.log(`WebSocket connection #${id}`);
    console.log('Connecting...');
    console.log('================================');

    /*
     * Очищаем данные предыдущего соединения
     */

    webhash = '';
    MyID = '';
    MyPass = '';
    MyNick = '';

    /*
     * Создаём WebSocket
     */

    const socket = new WebSocket(
        WS_URL,
        {
            rejectUnauthorized: false
        }
    );

    ws = socket;


    /*
     * CONNECTED
     */

    socket.on('open', () => {

        console.log(
            `[${id}] Connected`
        );

        /*
         * Если reconnect уже был запланирован,
         * отменяем его
         */

        clearTimeout(
            disconnectReconnectTimer
        );

        disconnectReconnectTimer = null;

        /*
         * IDENT
         */

        sendMessage(
            socket,
            ':ru IDENT 352 -2 4030 1 2 :GALA'
        );
    });


    /*
     * MESSAGE
     */

    socket.on('message', (data) => {

        const message = data.toString();

        console.log(
            '<<',
            message
        );

        const TS = message
            .split(' ')
            .map(item => item.trim());


        /*
         * HAAAPSI
         */

        if (TS[0] === 'HAAAPSI') {

            webhash = parse(
                TS[1]
            );

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

            console.log(
                'REGISTER:'
            );

            console.log(
                'ID:',
                MyID
            );

            console.log(
                'PASS:',
                MyPass
            );

            console.log(
                'NICK:',
                MyNick
            );


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

                }, 3000);

            }, 2000);


            console.log(
                'WebSocket authentication completed'
            );
        }
    });


    /*
     * CLOSE
     */

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
         * Если это всё ещё текущее соединение,
         * очищаем ws
         */

        if (ws === socket) {

            ws = null;
        }


        /*
         * Если reconnect уже был запланирован,
         * отменяем старый таймер
         */

        clearTimeout(
            disconnectReconnectTimer
        );


        /*
         * Через 5 секунд подключаемся снова
         */

        disconnectReconnectTimer = setTimeout(() => {

            console.log('');

            console.log(
                '================================'
            );

            console.log(
                '5 SECONDS PASSED'
            );

            console.log(
                'Auto reconnecting WebSocket...'
            );

            console.log(
                '================================'
            );


            disconnectReconnectTimer = null;

            connect();

        }, DISCONNECT_RECONNECT_DELAY);
    });


    /*
     * ERROR
     */

    socket.on('error', (err) => {

        console.error(
            `[${id}] WebSocket error:`,
            err.message
        );

        /*
         * Отдельный reconnect здесь НЕ запускаем.
         *
         * После error обычно приходит close,
         * а reconnect выполняется там.
         */
    });


    /*
     * PING
     */

    socket.on('ping', () => {

        console.log(
            `[${id}] WebSocket ping`
        );
    });
}


/*
 * SEND MESSAGE
 */

function sendMessage(
    socket,
    text
) {

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


/*
 * WEBHASH
 */

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
