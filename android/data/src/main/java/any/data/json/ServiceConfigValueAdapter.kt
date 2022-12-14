package any.data.json

import any.data.entity.ServiceConfigValue
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.ToJson

class ServiceConfigValueAdapter {
    @ToJson
    fun toJson(value: ServiceConfigValue): String {
        return when (value) {
            is ServiceConfigValue.Boolean -> Json.toJson(value.value, Boolean::class.java)
            is ServiceConfigValue.Double -> Json.toJson(value.value, Double::class.java)
            is ServiceConfigValue.String -> Json.toJson(value.value, String::class.java)

            is ServiceConfigValue.CookiesAndUa -> Json.toJson(
                value, ServiceConfigValue.CookiesAndUa::class.java
            )
        }
    }

    @FromJson
    fun fromJson(value: Any): ServiceConfigValue {
        return when (value) {
            is Boolean -> ServiceConfigValue.Boolean(value)

            is Double -> ServiceConfigValue.Double(value)

            is String -> {
                if (value.length >= 2) {
                    val stringValue = try {
                        Json.fromJson(value, String::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    if (stringValue != null) {
                        return ServiceConfigValue.String(stringValue)
                    }
                }

                val boolVal = value.toBooleanStrictOrNull()
                if (boolVal != null) {
                    return ServiceConfigValue.Boolean(boolVal)
                }

                val doubleVal = value.toDoubleOrNull()
                if (doubleVal != null) {
                    return ServiceConfigValue.Double(doubleVal)
                }

                try {
                    return Json.fromJson(value, ServiceConfigValue.CookiesAndUa::class.java)!!
                } catch (e: Exception) {
                    e.printStackTrace()
                    ServiceConfigValue.String(value)
                }
            }

            else -> {
                throw JsonDataException(
                    "Unknown config value: $value, class: ${value::class.java}"
                )
            }
        }
    }
}