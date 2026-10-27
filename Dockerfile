FROM php:8.2-apache

# Install system dependencies and PHP extensions required by phpBB #libjpeg-dev \
RUN apt-get update && apt-get install -y \
    libpng-dev \
    libxml2-dev \
    libzip-dev \
    zlib1g-dev \
    libonig-dev \
    libfreetype6-dev \
    libjpeg62-turbo-dev \
    libwebp-dev \
    libxpm-dev \
    && docker-php-ext-configure gd \
       --with-freetype \
       --with-jpeg \
    && docker-php-ext-install \
       gd \
       mysqli \
       pdo_mysql \
       mbstring \
       xml \
       zip \
       ftp \
    && a2enmod rewrite

# Enable Apache rewrite module for clean URLs
RUN a2enmod rewrite
#??? What the hell????

EXPOSE 80
