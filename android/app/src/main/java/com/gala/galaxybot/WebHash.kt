package com.gala.galaxybot

import java.security.MessageDigest

/**
 * Порт функции `parse()` из server.js:
 *
 * ```js
 * const hash = crypto.createHash('md5').update(e).digest('hex');
 * return hash.split('').reverse().join('0').substr(5, 10);
 * ```
 *
 * Сервер присылает `HAAAPSI <значение>`, а клиент отвечает вычисленным отсюда webhash
 * в команде `USER <id> <pass> <nick> <webhash>`.
 */
object WebHash {

    /** Возвращает webhash или `null`, если входная строка пустая. */
    fun compute(input: String?): String? {
        if (input.isNullOrEmpty()) return null

        return try {
            val digest = MessageDigest.getInstance("MD5")
                .digest(input.toByteArray(Charsets.UTF_8))

            // hex-строка md5 (32 символа, нижний регистр)
            val hex = digest.joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

            // split('').reverse().join('0') -> 63 символа
            val joined = hex.reversed().toCharArray().joinToString("0")

            // substr(5, 10) -> 10 символов начиная с индекса 5
            joined.substring(5, 15)
        } catch (e: Exception) {
            null
        }
    }
}
