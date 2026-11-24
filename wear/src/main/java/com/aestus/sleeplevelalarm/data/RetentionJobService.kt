package com.aestus.sleeplevelalarm.data

import android.app.job.JobParameters
import android.app.job.JobService
import com.aestus.sleeplevelalarm.SleepApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RetentionJobService : JobService() {
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = (application as SleepApplication).scope.launch {
            val failed = runCatching { (application as SleepApplication).repository.prune(System.currentTimeMillis()) }.isFailure
            jobFinished(params, failed)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
}
