/*****************************************************************************
 * cpu.c: cpu detection
 *****************************************************************************
 * Copyright (C) 2003-2016 x264 project
 *
 * Authors: Loren Merritt <lorenm@u.washington.edu>
 *          Laurent Aimar <fenrir@via.ecp.fr>
 *          Fiona Glaser <fiona@x264.com>
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

#if HAVE_POSIXTHREAD && SYS_LINUX
#include <sched.h>
#endif
#if SYS_BEOS
#include <kernel/OS.h>
#endif
#if SYS_MACOSX || SYS_FREEBSD
#include <sys/types.h>
#include <sys/sysctl.h>
#endif
#if SYS_OPENBSD
#include <sys/param.h>
#include <sys/sysctl.h>
#include <machine/cpu.h>
#endif

const x264_cpu_name_t x264_cpu_names[] =
{
#if HAVE_MMX
//  {"MMX",         X264_CPU_MMX},  // we don't support asm on mmx1 cpus anymore
//  {"CMOV",        X264_CPU_CMOV}, // we require this unconditionally, so don't print it
#define MMX2 X264_CPU_MMX|X264_CPU_MMX2|X264_CPU_CMOV
    {"MMX2",        MMX2},
    {"MMXEXT",      MMX2},
    {"SSE",         MMX2|X264_CPU_SSE},
#define SSE2 MMX2|X264_CPU_SSE|X264_CPU_SSE2
    {"SSE2Slow",    SSE2|X264_CPU_SSE2_IS_SLOW},
    {"SSE2",        SSE2},
    {"SSE2Fast",    SSE2|X264_CPU_SSE2_IS_FAST},
    {"SSE3",        SSE2|X264_CPU_SSE3},
    {"SSSE3",       SSE2|X264_CPU_SSE3|X264_CPU_SSSE3},
    {"SSE4.1",      SSE2|X264_CPU_SSE3|X264_CPU_SSSE3|X264_CPU_SSE4},
    {"SSE4",        SSE2|X264_CPU_SSE3|X264_CPU_SSSE3|X264_CPU_SSE4},
    {"SSE4.2",      SSE2|X264_CPU_SSE3|X264_CPU_SSSE3|X264_CPU_SSE4|X264_CPU_SSE42},
#define AVX SSE2|X264_CPU_SSE3|X264_CPU_SSSE3|X264_CPU_SSE4|X264_CPU_SSE42|X264_CPU_AVX
    {"AVX",         AVX},
    {"XOP",         AVX|X264_CPU_XOP},
    {"FMA4",        AVX|X264_CPU_FMA4},
    {"FMA3",        AVX|X264_CPU_FMA3},
    {"AVX2",        AVX|X264_CPU_FMA3|X264_CPU_AVX2},
#undef AVX
#undef SSE2
#undef MMX2
    {"Cache32",         X264_CPU_CACHELINE_32},
    {"Cache64",         X264_CPU_CACHELINE_64},
    {"LZCNT",           X264_CPU_LZCNT},
    {"BMI1",            X264_CPU_BMI1},
    {"BMI2",            X264_CPU_BMI1|X264_CPU_BMI2},
    {"SlowCTZ",         X264_CPU_SLOW_CTZ},
    {"SlowAtom",        X264_CPU_SLOW_ATOM},
    {"SlowPshufb",      X264_CPU_SLOW_PSHUFB},
    {"SlowPalignr",     X264_CPU_SLOW_PALIGNR},
    {"SlowShuffle",     X264_CPU_SLOW_SHUFFLE},
    {"UnalignedStack",  X264_CPU_STACK_MOD4},
#elif ARCH_PPC
    {"Altivec",         X264_CPU_ALTIVEC},
#elif ARCH_ARM
    {"ARMv6",           X264_CPU_ARMV6},
    {"NEON",            X264_CPU_NEON},
    {"FastNeonMRC",     X264_CPU_FAST_NEON_MRC},
#elif ARCH_AARCH64
    {"ARMv8",           X264_CPU_ARMV8},
    {"NEON",            X264_CPU_NEON},
#elif ARCH_MIPS
    {"MSA",             X264_CPU_MSA},
#endif
    {"", 0},
};

#if (ARCH_PPC && SYS_LINUX) || (ARCH_ARM && !HAVE_NEON)
#include <signal.h>
#include <setjmp.h>
static sigjmp_buf jmpbuf;
static volatile sig_atomic_t canjump = 0;

static void sigill_handler( int sig )
{
    if( !canjump )
    {
        signal( sig, SIG_DFL );
        raise( sig );
    }

    canjump = 0;
    siglongjmp( jmpbuf, 1 );
}
#endif

#if HAVE_MMX
int x264_cpu_cpuid_test( void );
void x264_cpu_cpuid( uint32_t op, uint32_t *eax, uint32_t *ebx, uint32_t *ecx, uint32_t *edx );
void x264_cpu_xgetbv( uint32_t op, uint32_t *eax, uint32_t *edx );

uint32_t x264_cpu_detect( void )
{
    uint32_t cpu = 0;
    uint32_t eax, ebx, ecx, edx;
    uint32_t vendor[4] = {0};
    uint32_t max_extended_cap, max_basic_cap;
    int cache;

#if !ARCH_X86_64
    if( !x264_cpu_cpuid_test() )
        return 0;
#endif

    x264_cpu_cpuid( 0, &eax, vendor+0, vendor+2, vendor+1 );
    max_basic_cap = eax;
    if( max_basic_cap == 0 )
        return 0;

    x264_cpu_cpuid( 1, &eax, &ebx, &ecx, &edx );
    if( edx&0x00800000 )
        cpu |= X264_CPU_MMX;
    else
        return cpu;
    if( edx&0x02000000 )
        cpu |= X264_CPU_MMX2|X264_CPU_SSE;
    if( edx&0x00008000 )
        cpu |= X264_CPU_CMOV;
    else
        return cpu;
    if( edx&0x04000000 )
        cpu |= X264_CPU_SSE2;
    if( ecx&0x00000001 )
        cpu |= X264_CPU_SSE3;
    if( ecx&0x00000200 )
        cpu |= X264_CPU_SSSE3;
    if( ecx&0x00080000 )
        cpu |= X264_CPU_SSE4;
    if( ecx&0x00100000 )
        cpu |= X264_CPU_SSE42;
    /* Check OXSAVE and AVX bits */
    if( (ecx&0x18000000) == 0x18000000 )
    {
        /* Check for OS support */
        x264_cpu_xgetbv( 0, &eax, &edx );
        if( (eax&0x6) == 0x6 )
        {
            cpu |= X264_CPU_AVX;
            if( ecx&0x00001000 )
                cpu |= X264_CPU_FMA3;
        }
    }

    if( max_basic_cap >= 7 )
    {
        x264_cpu_cpuid( 7, &eax, &ebx, &ecx, &edx );
        /* AVX2 requires OS support, but BMI1/2 don't. */
        if( (cpu&X264_CPU_AVX) && (ebx&0x00000020) )
            cpu |= X264_CPU_AVX2;
        if( ebx&0x00000008 )
        {
            cpu |= X264_CPU_BMI1;
            if( ebx&0x00000100 )
                cpu |= X264_CPU_BMI2;
        }
    }

    if( cpu & X264_CPU_SSSE3 )
        cpu |= X264_CPU_SSE2_IS_FAST;

    x264_cpu_cpuid( 0x80000000, &eax, &ebx, &ecx, &edx );
    max_extended_cap = eax;

    if( max_extended_cap >= 0x80000001 )
    {
        x264_cpu_cpuid( 0x80000001, &eax, &ebx, &ecx, &edx );

        if( ecx&0x00000020 )
            cpu |= X264_CPU_LZCNT;             /* Supported by Intel chips starting with Haswell */
        if( ecx&0x00000040 ) /* SSE4a, AMD only */
        {
            int family = ((eax>>8)&0xf) + ((eax>>20)&0xff);
            cpu |= X264_CPU_SSE2_IS_FAST;      /* Phenom and later CPUs have fast SSE units */
            if( family == 0x14 )
            {
                cpu &= ~X264_CPU_SSE2_IS_FAST; /* SSSE3 doesn't imply fast SSE anymore... */
                cpu |= X264_CPU_SSE2_IS_SLOW;  /* Bobcat has 64-bit SIMD units */
                cpu |= X264_CPU_SLOW_PALIGNR;  /* palignr is insanely slow on Bobcat */
            }
            if( family == 0x16 )
            {
                cpu |= X264_CPU_SLOW_PSHUFB;   /* Jaguar's pshufb isn't that slow, but it's slow enough
                                                * compared to alternate instruction sequences that this
                                                * is equal or faster on almost all such functions. */
            }
        }

        if( cpu & X264_CPU_AVX )
        {
            if( ecx&0x00000800 ) /* XOP */
                cpu |= X264_CPU_XOP;
            if( ecx&0x00010000 ) /* FMA4 */
                cpu |= X264_CPU_FMA4;
        }

        if( !strcmp((char*)vendor, "AuthenticAMD") )
        {
            if( edx&0x00400000 )
                cpu |= X264_CPU_MMX2;
            if( !(cpu&X264_CPU_LZCNT) )
                cpu |= X264_CPU_SLOW_CTZ;
            if( (cpu&X264_CPU_SSE2) && !(cpu&X264_CPU_SSE2_IS_FAST) )
                cpu |= X264_CPU_SSE2_IS_SLOW; /* AMD CPUs come in two types: terrible at SSE and great at it */
        }
    }

    if( !strcmp((char*)vendor, "GenuineIntel") )
    {
        x264_cpu_cpuid( 1, &eax, &ebx, &ecx, &edx );
        int family = ((eax>>8)&0xf) + ((eax>>20)&0xff);
        int model  = ((eax>>4)&0xf) + ((eax>>12)&0xf0);
        if( family == 6 )
        {
            /* 6/9 (pentium-m "banias"), 6/13 (pentium-m "dothan"), and 6/14 (core1 "yonah")
             * theoretically support sse2, but it's significantly slower than mmx for
             * almost all of x264's functions, so let's just pretend they don't. */
            if( model == 9 || model == 13 || model == 14 )
            {
                cpu &= ~(X264_CPU_SSE2|X264_CPU_SSE3);
                assert(!(cpu&(X264_CPU_SSSE3|X264_CPU_SSE4)));
            }
            /* Detect Atom CPU */
            else if( model == 28 )
            {
                cpu |= X264_CPU_SLOW_ATOM;
                cpu |= X264_CPU_SLOW_CTZ;
                cpu |= X264_CPU_SLOW_PSHUFB;
            }
            /* Conroe has a slow shuffle unit. Check the model number to make sure not
             * to include crippled low-end Penryns and Nehalems that don't have SSE4. */
            else if( (cpu&X264_CPU_SSSE3) && !(cpu&X264_CPU_SSE4) && model < 23 )
                cpu |= X264_CPU_SLOW_SHUFFLE;
        }
    }

    if( (!strcmp((char*)vendor, "GenuineIntel") || !strcmp((char*)vendor, "CyrixInstead")) && !(cpu&X264_CPU_SSE42))
    {
        /* cacheline size is specified in 3 places, any of which may be missing */
        x264_cpu_cpuid( 1, &eax, &ebx, &ecx, &edx );
        cache = (ebx&0xff00)>>5; // cflush size
        if( !cache && max_extended_cap >= 0x80000006 )
        {
            x264_cpu_cpuid( 0x80000006, &eax, &ebx, &ecx, &edx );
            cache = ecx&0xff; // cacheline size
        }
        if( !cache && max_basic_cap >= 2 )
        {
            // Cache and TLB Information
            static const char cache32_ids[] = { 0x0a, 0x0c, 0x41, 0x42, 0x43, 0x44, 0x45, 0x82, 0x83, 0x84, 0x85, 0 };
            static const char cache64_ids[] = { 0x22, 0x23, 0x25, 0x29, 0x2c, 0x46, 0x47, 0x49, 0x60, 0x66, 0x67,
                                                0x68, 0x78, 0x79, 0x7a, 0x7b, 0x7c, 0x7c, 0x7f, 0x86, 0x87, 0 };
            uint32_t buf[4];
            int max, i = 0;
            do {
                x264_cpu_cpuid( 2, buf+0, buf+1, buf+2, buf+3 );
                max = buf[0]&0xff;
                buf[0] &= ~0xff;
                for( int j = 0; j < 4; j++ )
                    if( !(buf[j]>>31) )
                        while( buf[j] )
                        {
                            if( strchr( cache32_ids, buf[j]&0xff ) )
                                cache = 32;
                            if( strchr( cache64_ids, buf[j]&0xff ) )
                                cache = 64;
                            buf[j] >>= 8;
                        }
            } while( ++i < max );
        }

        if( cache == 32 )
            cpu |= X264_CPU_CACHELINE_32;
        else if( cache == 64 )
            cpu |= X264_CPU_CACHELINE_64;
        else
            x264_log( NULL, X264_LOG_WARNING, "unable to determine cacheline size\n" );
    }

#if STACK_ALIGNMENT < 16
    cpu |= X264_CPU_STACK_MOD4;
#endif

    return cpu;
}

#elif ARCH_PPC && HAVE_ALTIVEC

#if SYS_MACOSX || SYS_OPENBSD || SYS_FREEBSD
#include <sys/sysctl.h>
uint32_t x264_cpu_detect( void )
{
    /* Thank you VLC */
    uint32_t cpu = 0;
#if SYS_OPENBSD
    int      selectors[2] = { CTL_MACHDEP, CPU_ALTIVEC };
#elif SYS_MACOSX
    int      selectors[2] = { CTL_HW, HW_VECTORUNIT };
#endif
    int      has_altivec = 0;
    size_t   length = sizeof( has_altivec );
#if SYS_MACOSX || SYS_OPENBSD
    int      error = sysctl( selectors, 2, &has_altivec, &length, NULL, 0 );
#else
    int      error = sysctlbyname( "hw.altivec", &has_altivec, &length, NULL, 0 );
#endif

    if( error == 0 && has_altivec != 0 )
        cpu |= X264_CPU_ALTIVEC;

    return cpu;
}

#elif SYS_LINUX

uint32_t x264_cpu_detect( void )
{
#ifdef __NO_FPRS__
    return 0;
#else
    static void (*oldsig)( int );

    oldsig = signal( SIGILL, sigill_handler );
    if( sigsetjmp( jmpbuf, 1 ) )
    {
        signal( SIGILL, oldsig );
        return 0;
    }

    canjump = 1;
    asm volatile( "mtspr 256, %0\n\t"
                  "vand 0, 0, 0\n\t"
                  :
                  : "r"(-1) );
    canjump = 0;

    signal( SIGILL, oldsig );

    return X264_CPU_ALTIVEC;
#endif
}
#endif

#elif ARCH_ARM

void x264_cpu_neon_test( void );
int x264_cpu_fast_neon_mrc_test( void );

uint32_t x264_cpu_detect( void )
{
    int flags = 0;
#if HAVE_ARMV6
    flags |= X264_CPU_ARMV6;

    // don't do this hack if compiled with -mfpu=neon
#if !HAVE_NEON
    static void (* oldsig)( int );
    oldsig = signal( SIGILL, sigill_handler );
    if( sigsetjmp( jmpbuf, 1 ) )
    {
        signal( SIGILL, oldsig );
        return flags;
    }

    canjump = 1;
    x264_cpu_neon_test();
    canjump = 0;
    signal( SIGILL, oldsig );
#endif

    flags |= X264_CPU_NEON;

    // fast neon -> arm (Cortex-A9) detection relies on user access to the
    // cycle counter; this assumes ARMv7 performance counters.
    // NEON requires at least ARMv7, ARMv8 may require changes here, but
    // hopefully this hacky detection method will have been replaced by then.
    // Note that there is potential for a race condition if another program or
    // x264 instance disables or reinits the counters while x264 is using them,
    // which may result in incorrect detection and the counters stuck enabled.
    // right now Apple does not seem to support performance counters for this test
#ifndef __MACH__
    flags |= x264_cpu_fast_neon_mrc_test() ? X264_CPU_FAST_NEON_MRC : 0;
#endif
    // TODO: write dual issue test? currently it's A8 (dual issue) vs. A9 (fast mrc)
#endif
    return flags;
}

#elif ARCH_AARCH64

uint32_t x264_cpu_detect( void )
{
    return X264_CPU_ARMV8 | X264_CPU_NEON;
}

#elif ARCH_MIPS

uint32_t x264_cpu_detect( void )
{
    uint32_t flags = 0;
#if HAVE_MSA
    flags |= X264_CPU_MSA;
#endif
    return flags;
}

#else

uint32_t x264_cpu_detect( void )
{
    return 0;
}

#endif

int x264_cpu_num_processors( void )
{
#if !HAVE_THREAD
    return 1;

#elif SYS_WINDOWS
    return x264_pthread_num_processors_np();

#elif SYS_CYGWIN || SYS_SunOS
    return sysconf( _SC_NPROCESSORS_ONLN );

#elif SYS_LINUX
#ifdef __ANDROID__
    // Android NDK does not expose sched_getaffinity
    return sysconf( _SC_NPROCESSORS_CONF );
#else
    cpu_set_t p_aff;
    memset( &p_aff, 0, sizeof(p_aff) );
    if( sched_getaffinity( 0, sizeof(p_aff), &p_aff ) )
        return 1;
#if HAVE_CPU_COUNT
    return CPU_COUNT(&p_aff);
#else
    int np = 0;
    for( unsigned int bit = 0; bit < 8 * sizeof(p_aff); bit++ )
        np += (((uint8_t *)&p_aff)[bit / 8] >> (bit % 8)) & 1;
    return np;
#endif
#endif

#elif SYS_BEOS
    system_info info;
    get_system_info( &info );
    return info.cpu_count;

#elif SYS_MACOSX || SYS_FREEBSD || SYS_OPENBSD
    int ncpu;
    size_t length = sizeof( ncpu );
#if SYS_OPENBSD
    int mib[2] = { CTL_HW, HW_NCPU };
    if( sysctl(mib, 2, &ncpu, &length, NULL, 0) )
#else
    if( sysctlbyname("hw.ncpu", &ncpu, &length, NULL, 0) )
#endif
    {
        ncpu = 1;
    }
    return ncpu;

#else
    return 1;
#endif
}
