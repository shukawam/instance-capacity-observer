package me.shukawam;

public class ServiceException extends RuntimeException {

    public ServiceException() {
        super();
    }

    public ServiceException(Throwable e) {
        super(e);
    }

    public ServiceException(String arg) {
        super(arg);
    }

    public ServiceException(String arg, Throwable e) {
        super(arg, e);
    }

}
