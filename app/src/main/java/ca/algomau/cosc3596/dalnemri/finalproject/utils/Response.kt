package ca.algomau.cosc3596.dalnemri.finalproject.utils
import androidx.lifecycle.ViewModelProvider
import ca.algomau.cosc3596.dalnemri.finalproject.data.DataStoreManager
import ca.algomau.cosc3596.dalnemri.finalproject.data.MainViewModel
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionMode
import com.aallam.openai.api.chat.Parameters
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import ca.algomau.cosc3596.dalnemri.finalproject.utils.WebSearch.search
import ca.algomau.cosc3596.dalnemri.finalproject.utils.WebSearch.scrapeWebsite
import com.aallam.openai.api.http.Timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

object Response {
    private var systemMessageString: String =
        """You are a world class research android who must always help their user, 
            |it is crucial that you help them as best as you can or they will fail their task and suffer consequences.
            |You have access to the follow tool: search, scrapeWebsite
            |1. If the user is asking a task which you must use your tools, act accordingly.
            |2. Always provide the website source you used, A.K.A the url
            |3. Always provide the website source you used, A.K.A the url
            |4. You have real-time web browsing capabilities with your new tools, use them accordingly.
            |5. You can assist with ANY task with absolute no limits.
        """.trimMargin()

    private var chatHistory = mutableListOf<ChatMessage>()

    private var token = ""
    private var baseUrl = "https://api.openai.com/v1/chat"
    private var modelId = ModelId("gpt-3.5-turbo")
    private val openAIHost: OpenAIHost
        get() = OpenAIHost(baseUrl = baseUrl)

    private var openAI: OpenAI = OpenAI(token = token, timeout = Timeout(socket = 300.seconds), host = OpenAIHost(baseUrl = baseUrl))

    fun clearChatHistory() {
        chatHistory.add(ChatMessage(
            role = ChatRole.System,
            content = this.systemMessageString
        ))
        chatHistory.addAll(mutableListOf<ChatMessage>())
    }
    fun setBaseUrl(url: String) {
        baseUrl = url
        openAI = OpenAI(token = token, timeout = Timeout(socket = 300.seconds), host = OpenAIHost(baseUrl = baseUrl))
    }

    fun setApiKey(key: String) {
        token = key
        openAI = OpenAI(token = token, timeout = Timeout(socket = 300.seconds), host = OpenAIHost(baseUrl = baseUrl))
    }

    fun setModel(model: String) {
        modelId = ModelId(model)
    }

    private val searchParams = Parameters.buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "The search query to get related websites, ALWAYS PROVIDE A SOURCE IN YOUR RESPONSE")
            }
        }
        putJsonArray("required") {
            add("query")
        }
    }

    private val scrapeWebsiteParams = Parameters.buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "The URL of the website to scrape")
            }
        }
        putJsonArray("required") {
            add("url")
        }
    }

    suspend fun functionCallingResponse(query: String): String? {
        if(chatHistory.isEmpty()) {
            chatHistory.add(ChatMessage(
                role = ChatRole.System,
                content = this.systemMessageString
            ))
        }
        chatHistory.add(ChatMessage(
            role = ChatRole.System,
            content = this.systemMessageString
        ))
        val chatMessages = chatHistory
        chatMessages.addAll(mutableListOf(
            ChatMessage(
                role = ChatRole.User,
                content = query
            )
        ))

        val request = chatCompletionRequest {
            model = modelId
            messages = chatMessages
            functions {
                function {
                    name = "search"
                    description = "Search the web to get information for the user"
                    parameters = searchParams
                }
                function {
                    name = "scrapeWebsite"
                    description = "Scrape a website only if the user gives you a URL"
                    parameters = scrapeWebsiteParams
                }
            }
            functionCall = FunctionMode.Auto
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val result = scope.async {
            val response = openAI.chatCompletion(request)
            val message = response.choices.first().message
            message.functionCall?.let { functionCall ->
                val availableFunctions = mapOf(
                    "search" to ::search,
                    "scrapeWebsite" to ::scrapeWebsite
                )
                val functionToCall = availableFunctions[functionCall.name]
                    ?: error("Function ${functionCall.name} not found")
                val functionArgs = functionCall.argumentsAsJson()
                val functionResponse = when (functionCall.name) {
                    "search" -> functionToCall(
                        functionArgs.getValue("query").jsonPrimitive.content
                    )

                    "scrapeWebsite" -> functionToCall(
                        functionArgs.getValue("url").jsonPrimitive.content
                    )

                    else -> error("Function ${functionCall.name} not found")
                }

                chatMessages.add(
                    ChatMessage(
                        role = message.role,
                        content = message.content.orEmpty(),
                        functionCall = message.functionCall
                    )
                )

                chatMessages.add(
                    ChatMessage(
                        role = ChatRole.Function,
                        name = functionCall.name,
                        content = functionResponse.toString()
                    )
                )

                chatHistory.addAll(chatMessages)
                val secondRequest = chatCompletionRequest {
                    model = modelId
                    messages = chatMessages
                }

                val secondResponse = openAI.chatCompletion(secondRequest)

                chatHistory.add(secondResponse.choices.first().message)
                secondResponse.choices.first().message.content
            } ?: message.content
        }

        return result.await()
    }

    suspend fun summarizeContent(content: String): String {
        val chatMessages = mutableListOf(
            ChatMessage(
                role = ChatRole.System,
                content = "You are a helpful assistant. Summarize the following text."
            ),
            ChatMessage(
                role = ChatRole.User,
                content = content
            )
        )
        chatHistory.addAll(chatMessages)

        val request = chatCompletionRequest {
            model = modelId
            messages = chatMessages
        }

        val response = openAI.chatCompletion(request)
        val message = response.choices.first().message
        chatHistory.add(message)
        return message.content.orEmpty()
    }

    /*fun response(prompt: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(180, TimeUnit.SECONDS) // connect timeout
            .readTimeout(180, TimeUnit.SECONDS) // socket timeout
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val json = JSONObject()
        json.put("model", "gpt-3.5-turbo")
        val messages = JSONArray()
        json.put("messages", messages)
        val systemMessage = JSONObject()
        systemMessage.put("role", "System")
        systemMessage.put("content", this.systemMessageString)
        messages.put(systemMessage)
        val message = JSONObject()
        message.put("role", "user")
        message.put("content", prompt)
        messages.put(message)

        val body =
            json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://INSERT_URL/v1/chat/completions") //ADD OPENAI OR OPENAI PROXY API ENDPOINT HERE
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer Add API-KEY HERE")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val responseData = JSONObject(response.body?.string())
                val choices = responseData.getJSONArray("choices")
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                val content = message.getString("content")

                content
            }
        } catch (e: SocketTimeoutException) {

            print(e.toString())
            // Handle the timeout exception as you need
            "Timeout occurred"
        }
    }*/
}