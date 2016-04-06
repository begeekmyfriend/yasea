package net.ossrs.sea.rtmp.io;

/**
 * Simple interface for the "parent" of one or more worker threads, so that
 * these worker threads can signal the parent if they stop (e.g. in the event of
 * parent/main thread not expecting a child thread to exit, such as when an irrecoverable
 * error has occurred in that child thread).
 * 
 * @author francois
 */
public interface ThreadController {
    
    /** Called when a child thread has exited its run() loop */
    void threadHasExited(Thread thread);
    
}
