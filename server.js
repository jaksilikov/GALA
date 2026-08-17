import WebSocket from 'ws';
import crypto from 'crypto';

const WS_URL = 'wss://cs.mobstudio.ru:6672/';
const RECONNECT_INTERVAL = 20 * 60 * 1000; // 20 минут

let ws = null;
let reconnectTimer = null;

let webhash = '';
let MyID = '';
let MyPass = '';
let MyNick = '';

function connect() {
    console.log('\n================================');
    console.log('Connecting to WebSocket...');
    console.log('================================');

    // Сбрасываем данные предыдущего подключения
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

        // Следующее подключение ровно через 20 минут
        clearTimeout(reconnectTimer);

        reconnectTimer = setTimeout(() => {
            console.log('\n20 minutes passed.33');
            console.log('Closing current WebSocket...');

            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.close();
            }

            // Новое подключение
            connect();

        }, RECONNECT_INTERVAL);
    });

    ws.on('message', async (data) => {
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

            console.log('webhash:', webhash);

            if (webhash) {
                sendMessage('RECOVER 5gr2x8c18k');
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

        /*
         * PING
         */
        if (TS[0] === 'PING') {
            sendMessage('PONG');
        }

        /*
         * SERVER 999
         */
       if (TS[0] === '999') {
    sendMessage('FWLISTVER 311');
    sendMessage('ADDONS 251824 1');
    sendMessage('MYADDONS 251824 1');
    sendMessage('PHONE 1920 1080 0 2 :chrome 151.0.0.0');

    sendMessage('JOIN ');

    console.log('JOIN sent');

    // Через 2 секунды
    setTimeout(() => {
        sendMessage('REMOVE 96');
        console.log('REMOVE 96 sent');

        // Ещё через 3 секунды (итого 5 секунд после JOIN)
        setTimeout(() => {
            sendMessage('OBJ_ACT 5 15170420 1 go_to_bed');
            console.log('OBJ_ACT sent');
        }, 3000);

    }, 2000);

    sendMessage('SLEEP ');

    console.log('WebSocket authentication completed');
}
    });

    /*
     * CLOSE
     *
     * Здесь НЕТ автоматического reconnect.
     * Новое соединение создаётся только таймером 20 минут.
     */
    ws.on('close', (code, reason) => {

        console.log(
            'Disconnected from WebSocket server'
        );

        console.log(
            'Close code:',
            code
        );

        console.log(
            'Close reason:',
            reason.toString()
        );
    });

    /*
     * ERROR
     */
    ws.on('error', (err) => {
        console.error(
            'WebSocket error:',
            err.message
        );
    });

    /*
     * PING
     */
    ws.on('ping', () => {
        console.log(
            'WebSocket ping received'
        );
    });
}


/*
 * SEND MESSAGE
 */
function sendMessage(text) {

    if (
        ws &&
        ws.readyState === WebSocket.OPEN
    ) {

        ws.send(
            text + ' \r\n'
        );

        console.log(
            '>>',
            text
        );

    } else {

        console.log(
            'WebSocket is not open. Message not sent:',
            text
        );

    }
}


/*
 * MD5 / WEBHASH
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


/*
 * FIRST CONNECTION
 */
connect();
