package com.vishwesh.repolens.ingest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
public interface HostResolver {

  List<InetAddress> resolve(String host) throws UnknownHostException;
}
