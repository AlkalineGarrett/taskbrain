package org.alkaline.taskbrain.data

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrompterAgent {

    // Initialize the Generative Model
    // backend = GenerativeBackend.googleAI() uses the Google AI (Gemini) backend
    private val model = Firebase.ai(backend = GenerativeBackend.googleAI())
        .generativeModel("gemini-2.5-flash")

    /**
     * Processes a user command to modify the current content.
     *
     * @param currentContent The current text in the editor.
     * @param command The instruction from the user.
     * @return The updated content.
     */
    suspend fun processCommand(currentContent: String, command: String): String {
        if (!ConnectivityMonitor.isOnline.value) {
            throw OfflineException("AI features require an internet connection")
        }
        return withContext(Dispatchers.IO) {
            // Construct the prompt
            // This is where we can elaborate on the prompt engineering or add an agent loop
            val prompt = """
                You are an AI assistant helping a user write a note.
                
                Current Content:
                $currentContent
                
                User Command:
                $command
                
                Please provide the updated content based on the command. 
                Unless the user explicitly requests the content to be cleared or replaced, retain it by repeating it in your output.
                Return ONLY the updated content. Do not include conversational fillers or markdown code fences (like ```) unless the content itself requires them.
                
            """.trimIndent()

            try {
                val response = model.generateContent(prompt)
                response.text ?: currentContent
            } catch (e: Exception) {
                // In a real app, you might want to handle specific errors
                throw e
            }
        }
    }
}
