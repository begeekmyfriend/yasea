/*****************************************************************************
 * osdep.c: platform-specific code
 *****************************************************************************
 * Copyright (C) 2003-2016 x264 project
 *
 * Authors: Steven Walters <kemuri9@gmail.com>
 *          Laurent Aimar <fenrir@via.ecp.fr>
 *          Henrik Gramner <henrik@gramner.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02111, USA.
 *
 * This program is also available under a commercial proprietary license.
 * For more information, contact us at licensing@x264.com.
 *****************************************************************************/

#include "common.h"

#ifdef _WIN32
#include <windows.h>
#include <io.h>
#endif

#if SYS_WINDOWS
#include <sys/types.h>
#include <sys/timeb.h>
#else
#include <sys/time.h>
#endif
#include <time.h>

#if PTW32_STATIC_LIB
/* this is a global in pthread-win32 to indicate if it has been initialized or not */
extern int ptw32_processInitialized;
#endif

int64_t x264_mdate( void )
{
#if SYS_WINDOWS
    struct timeb tb;
    ftime( &tb );
    return ((int64_t)tb.time * 1000 + (int64_t)tb.millitm) * 1000;
#else
    struct timeval tv_date;
    gettimeofday( &tv_date, NULL );
    return (int64_t)tv_date.tv_sec * 1000000 + (int64_t)tv_date.tv_usec;
#endif
}

#if HAVE_WIN32THREAD || PTW32_STATIC_LIB
/* state of the threading library being initialized */
static volatile LONG x264_threading_is_init = 0;

static void x264_threading_destroy( void )
{
#if PTW32_STATIC_LIB
    pthread_win32_thread_detach_np();
    pthread_win32_process_detach_np();
#else
    x264_win32_threading_destroy();
#endif
}

int x264_threading_init( void )
{
    /* if already init, then do nothing */
    if( InterlockedCompareExchange( &x264_threading_is_init, 1, 0 ) )
        return 0;
#if PTW32_STATIC_LIB
    /* if static pthread-win32 is already initialized, then do nothing */
    if( ptw32_processInitialized )
        return 0;
    if( !pthread_win32_process_attach_np() )
        return -1;
#else
    if( x264_win32_threading_init() )
        return -1;
#endif
    /* register cleanup to run at process termination */
    atexit( x264_threading_destroy );

    return 0;
}
#endif

#ifdef _WIN32
/* Functions for dealing with Unicode on Windows. */
FILE *x264_fopen( const char *filename, const char *mode )
{
    wchar_t filename_utf16[MAX_PATH];
    wchar_t mode_utf16[16];
    if( utf8_to_utf16( filename, filename_utf16 ) && utf8_to_utf16( mode, mode_utf16 ) )
        return _wfopen( filename_utf16, mode_utf16 );
    return NULL;
}

int x264_rename( const char *oldname, const char *newname )
{
    wchar_t oldname_utf16[MAX_PATH];
    wchar_t newname_utf16[MAX_PATH];
    if( utf8_to_utf16( oldname, oldname_utf16 ) && utf8_to_utf16( newname, newname_utf16 ) )
    {
        /* POSIX says that rename() removes the destination, but Win32 doesn't. */
        _wunlink( newname_utf16 );
        return _wrename( oldname_utf16, newname_utf16 );
    }
    return -1;
}

int x264_stat( const char *path, x264_struct_stat *buf )
{
    wchar_t path_utf16[MAX_PATH];
    if( utf8_to_utf16( path, path_utf16 ) )
        return _wstati64( path_utf16, buf );
    return -1;
}

#if !HAVE_WINRT
int x264_vfprintf( FILE *stream, const char *format, va_list arg )
{
    HANDLE console = NULL;
    DWORD mode;

    if( stream == stdout )
        console = GetStdHandle( STD_OUTPUT_HANDLE );
    else if( stream == stderr )
        console = GetStdHandle( STD_ERROR_HANDLE );

    /* Only attempt to convert to UTF-16 when writing to a non-redirected console screen buffer. */
    if( GetConsoleMode( console, &mode ) )
    {
        char buf[4096];
        wchar_t buf_utf16[4096];
        va_list arg2;

        va_copy( arg2, arg );
        int length = vsnprintf( buf, sizeof(buf), format, arg2 );
        va_end( arg2 );

        if( length > 0 && length < sizeof(buf) )
        {
            /* WriteConsoleW is the most reliable way to output Unicode to a console. */
            int length_utf16 = MultiByteToWideChar( CP_UTF8, 0, buf, length, buf_utf16, sizeof(buf_utf16)/sizeof(wchar_t) );
            DWORD written;
            WriteConsoleW( console, buf_utf16, length_utf16, &written, NULL );
            return length;
        }
    }
    return vfprintf( stream, format, arg );
}

int x264_is_pipe( const char *path )
{
    wchar_t path_utf16[MAX_PATH];
    if( utf8_to_utf16( path, path_utf16 ) )
        return WaitNamedPipeW( path_utf16, 0 );
    return 0;
}
#endif

#if defined(_MSC_VER) && _MSC_VER < 1900
/* MSVC pre-VS2015 has broken snprintf/vsnprintf implementations which are incompatible with C99. */
int x264_snprintf( char *s, size_t n, const char *fmt, ... )
{
    va_list arg;
    va_start( arg, fmt );
    int length = x264_vsnprintf( s, n, fmt, arg );
    va_end( arg );
    return length;
}

int x264_vsnprintf( char *s, size_t n, const char *fmt, va_list arg )
{
    int length = -1;

    if( n )
    {
        va_list arg2;
        va_copy( arg2, arg );
        length = _vsnprintf( s, n, fmt, arg2 );
        va_end( arg2 );

        /* _(v)snprintf adds a null-terminator only if the length is less than the buffer size. */
        if( length < 0 || length >= n )
            s[n-1] = '\0';
    }

    /* _(v)snprintf returns a negative number if the length is greater than the buffer size. */
    if( length < 0 )
        return _vscprintf( fmt, arg );

    return length;
}
#endif
#endif
