cmake_minimum_required(VERSION 3.22.1)
project(enginehost_sandbox C)

# The :runtime process's network filter; see runtime_sandbox.c.
add_library(enginehost_sandbox SHARED runtime_sandbox.c)
