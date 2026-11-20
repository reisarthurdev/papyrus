package dev.reisarthur.papyrus.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Execução em segundo plano sem dependências externas.
 * [io] para trabalho pesado (importação, paginação, busca), [main] para voltar à UI.
 */
object Bg {
    private const val TAG = "papyrus"
    private val pool = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun io(task: () -> Unit): Future<*> = pool.submit {
        try {
            task()
        } catch (t: Throwable) {
            Log.e(TAG, "Erro em tarefa de segundo plano", t)
        }
    }

    /** Roda [work] fora da UI e entrega o resultado (ou o erro) na thread principal. */
    fun <T> load(work: () -> T, onResult: (Result<T>) -> Unit): Future<*> = pool.submit {
        val result = runCatching(work)
        result.exceptionOrNull()?.let { Log.e(TAG, "Falha ao carregar", it) }
        mainHandler.post { onResult(result) }
    }

    fun main(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) task() else mainHandler.post(task)
    }

    fun mainDelayed(delayMs: Long, task: Runnable) = mainHandler.postDelayed(task, delayMs)
    fun cancel(task: Runnable) = mainHandler.removeCallbacks(task)
}
