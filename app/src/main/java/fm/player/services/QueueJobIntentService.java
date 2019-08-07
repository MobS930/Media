package fm.player.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.JobIntentService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * extension of JobIntentService with helpers to check if tasks queue is empty
 */
public abstract class QueueJobIntentService extends JobIntentService {

    private static final String TAG = "QueueJobIntentService";
    private static final String EXTRA_WORK_ID = "EXTRA_WORK_ID";

    private String mName;

    //internal queue of jobs
    private static final HashMap<ComponentName, JobQueue> sJobWorkQueue = new HashMap<>();

    /**
     * Creates an IntentService. Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    public QueueJobIntentService(String name) {
        super();
        mName = name;
    }

    public static void enqueueWork(@NonNull Context context, @NonNull Class cls, int jobId,
                                   @NonNull Intent work) {
        final ComponentName componentName = new ComponentName(context, cls);
        final long workId = System.currentTimeMillis();

        //used for internal queue implementation
        work.putExtra(EXTRA_WORK_ID, workId);

        //get queue for component
        JobQueue jobQueue = getJobQueue(componentName);

        //add workId
        jobQueue.add(workId);

        //save
        sJobWorkQueue.put(componentName, jobQueue);

        // enqueue Job
        JobIntentService.enqueueWork(context, cls, jobId, work);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        final ComponentName componentName = intent.getComponent();
        if (componentName != null && sJobWorkQueue.containsKey(componentName)) {

            final JobQueue jobQueue = sJobWorkQueue.get(componentName);
            final long workId = intent.getLongExtra(EXTRA_WORK_ID, -1);

            // intent is not queued in our internal queue (it was cleared) > SKIP WORK
            if (workId != -1 && !jobQueue.containsWorkId(workId)) {
                return;
            }

            //work will be handled, remove record from internal queue
            jobQueue.remove(workId);
        }
    }

    private static JobQueue getJobQueue(ComponentName componentName) {
        if (sJobWorkQueue.containsKey(componentName)) {
            return sJobWorkQueue.get(componentName);
        } else {
            return new JobQueue();
        }
    }

    /**
     * If return true- queue is empty so we can continue operations, if false-
     * something is in queue so drop operation and continue with next
     *
     * @return true if queue is empty, false if queue is non-empty
     */
    public boolean isContinue(@NonNull Context context, @NonNull Class cls) {
        final ComponentName componentName = new ComponentName(context, cls);
        return !sJobWorkQueue.containsKey(componentName) || sJobWorkQueue.get(componentName).isEmpty();
    }

    /**
     * clear internal queue for specific QueueJobIntentService
     *
     * @param cls implementation of QueueJobIntentService
     */
    public static void clearQueue(@NonNull Context context, @NonNull Class cls) {
        final ComponentName componentName = new ComponentName(context, cls);
        if (sJobWorkQueue.containsKey(componentName)) {
            sJobWorkQueue.put(componentName, new JobQueue());
        }
    }

    /**
     * object to hold info about internal job queue for specific QueueJobIntentService
     * it contains workIDs of intents
     */
    private static class JobQueue {

        private List<Long> workIDs = new ArrayList<>();

        public boolean containsWorkId(long workId) {
            return workIDs.contains(workId);
        }

        public void add(long workId) {
            workIDs.add(workId);
        }

        public boolean remove(long workId) {
            return workIDs.remove(workId);
        }

        public boolean isEmpty() {
            return workIDs.isEmpty();
        }
    }
}
