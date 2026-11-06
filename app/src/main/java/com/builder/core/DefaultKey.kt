package com.builder.core

import java.util.Base64

/**
 * Постоянный ключ подписи по умолчанию = ключ из app/debug.keystore (им подписаны сборки GitHub Actions).
 * Если ключа на телефоне ещё нет, берётся он, а не случайный: тогда сборки с любого телефона
 * ставятся поверх друг друга. Это публичный отладочный ключ; для релиза подставь свой («Ключ подписи» в настройках).
 */
object DefaultKey {
    private const val KEY_B64 =
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCQsKfvVyDQQbemGuiIRqxYZJ6YmOjDNdcpWRoByPST7gYtEdI+" +
        "EvK2atkBpL0zJxVX7tqgvfPdXPt0sw4qYZEPMzlnWniy5nbLusB431I/sc2BRApFS1PI3ArNf3pige5DdVMyJdv/RP3vc25KVCiu" +
        "+dG6uc86ke0SwiL1AmUJuzPJGc/+jD8azLjT+y275f5IdJSYkT9u7rHYQRAv2ehax6jB5eUKVvQLmWA4jXVi/EiUWoh3gsZL56LS" +
        "5NNjrHFAxuljPSF/HXM9VHEBHs4yZER1zVM32JJ0IKCvfWkHbju70LzA3JhghakFytGjQEhyQ2gZYCVVT2mwtrXmmzhPAgMBAAEC" +
        "ggEAIW1+zu/dN+AG8sxCrsFNLfrJHU4ucHMu63GXbZQk1UxnhuAYJ5kvE+/phNSvlXLRHoijYAGipqDFw3derXHvFojqGTOwsPxH" +
        "s9fIgeRuVyLX7KZwjQPTLpOPt1xdRQbpqhIgD3Tpt7cTx89uSqPY5lv3FWG6O7jdAblyZBKSj0WkwfDmJzfPlsHOIfYDN2gIU207" +
        "JN3Nl/3MtyAUf0QzsmdBTTt6Eoorhy3+W+GVY2DMop/XozIwUMsp4EuM5VItMxBTix1eE9+MfZ3jalWYOFPDQ47yYK5Qr/W81Dxq" +
        "fe+xLYaTclWbj6f1dT90Mj2C+TJt2jVdhsKD94DUvZYz0QKBgQDFweluQA+7yPeC+Zg0z1nn7eaI8ZCNtYbFaIng9sjyVNeL+Zvt" +
        "7w2aVIPyJIQhwYJyQrUrBR2bi4RKz9sgJ4O5CiIOTwPHtzq4YFfKPYQ2JWwnM6xyPUP+0j3R57I5wssOIXOJOFXIcJNvBiKzySiz" +
        "CDltlXm0HpJZjYe6U/LOpwKBgQC7Ta7iRlLfWxMg240Agi0r/OS9HUGcth6D4VbIb1hc7iqeQOmWBahNmnM5mUmJptDLHmyimU2F" +
        "djj+2YFaWmw02muYjnfsSEFoNyZCxJns2CIAbNnIaw5KC5X/3THaXlEwy3w15uND+HnOyIFSoEoeBEgLyL6prvxRyjOYKk3mGQKB" +
        "gBYJaMC/ZMFxQJuYwqxH2uXsbb8e5BPWgaEn6A1okrXmXGqdsxGNjP+AoX9vMEYusSGYa2XS2RzHLEPF684meRg6l9buWSZ9NY7s" +
        "NXw05LG/89Begr3sM9KGjRsB7atgGI6Hjb9hxrViThuWHHHaXa2tofCjMn+u1HIkQlayGiGLAoGBALZL2D9AZJ4gvRBIvwZUrHRY" +
        "kaQCGn7UvgRrSBSSUjU4VGBsGyw5W2IURbs0lt8TbbmTZfYnMM0JkFVyCjSPNyuFvhzf5JYiiQ5ORr3LLWjXvtgdGSW4FFM6zYYW" +
        "mdPH4kuUcNLhYiYgayU3/q4Vda2bSye0/OSXe9p66xZZbjHZAoGBALGhOJhCGslLAupXe3KqnRZtgS5cnO2ww50cATZsk+BU4+tn" +
        "8l20N1Lkg3oUtVvSSD8AXuERUNLtMtYzE4zxY+W3AnctW0SmmYaXFatxCpmi0nu6qXDZXnUWoN0BtcokZsnUBwh1qpf+xBvBDDMk" +
        "9D6bTJX1T0tyq6oTaX1bQKz3"
    private const val CERT_B64 =
        "MIIDIzCCAgugAwIBAgIIeXFjYbUMC38wDQYJKoZIhvcNAQEMBQAwPzELMAkGA1UEBhMCS1oxFDASBgNVBAoTC0FQSyBCdWlsZGVy" +
        "MRowGAYDVQQDExFBUEsgQnVpbGRlciBEZWJ1ZzAgFw0yNjEwMjkxNDE3NTRaGA8yMDU0MDMxNjE0MTc1NFowPzELMAkGA1UEBhMC" +
        "S1oxFDASBgNVBAoTC0FQSyBCdWlsZGVyMRowGAYDVQQDExFBUEsgQnVpbGRlciBEZWJ1ZzCCASIwDQYJKoZIhvcNAQEBBQADggEP" +
        "ADCCAQoCggEBAJCwp+9XINBBt6Ya6IhGrFhknpiY6MM11ylZGgHI9JPuBi0R0j4S8rZq2QGkvTMnFVfu2qC9891c+3SzDiphkQ8z" +
        "OWdaeLLmdsu6wHjfUj+xzYFECkVLU8jcCs1/emKB7kN1UzIl2/9E/e9zbkpUKK750bq5zzqR7RLCIvUCZQm7M8kZz/6MPxrMuNP7" +
        "Lbvl/kh0lJiRP27usdhBEC/Z6FrHqMHl5QpW9AuZYDiNdWL8SJRaiHeCxkvnotLk02OscUDG6WM9IX8dcz1UcQEezjJkRHXNUzfY" +
        "knQgoK99aQduO7vQvMDcmGCFqQXK0aNASHJDaBlgJVVPabC2teabOE8CAwEAAaMhMB8wHQYDVR0OBBYEFKRsFoGSFiG7SHX7k36c" +
        "qnzkUuxiMA0GCSqGSIb3DQEBDAUAA4IBAQAMihRdlRQL4tgsQrM/aMK4rJLqAQZoBp1yrHqkPQ1Ww47Z4kmqi//HtHLIaH5aGagV" +
        "DoEhfhOerp/hiYCx1GE651Mxv96CQ8hlLHhDKtrUjBOv3TZCOu2wrTjZhBsqxUHj4YyyEHtikv47viNisNO32R3Zvn2XaRL9bz0u" +
        "aLEoW5a4RmiaYaBOTqta0mqTkmNC05xr50wOCeswuMXzW47AbHWFIu/UwisrR3ATW8nP6l+FGwM3JBjpmhYdsl3XNv7rfrF1GrO5" +
        "xPdqGVwZPZewBxnU3jTd2RqU1Ahqd/kGsaHNdCos5+f+XGsF7IvozJDaYCAJoQjLEaI2+YO/AvKA"

    val key: ByteArray get() = Base64.getDecoder().decode(KEY_B64)
    val cert: ByteArray get() = Base64.getDecoder().decode(CERT_B64)
}
